/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.client.session;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.agent.common.AgentForwardSupport;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.future.GlobalRequestFuture;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.kex.KexState;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.helpers.AbstractConnectionService;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.x11.X11ForwardSupport;

/**
 * Client side <code>ssh-connection</code> service.
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class ClientConnectionService
        extends AbstractConnectionService
        implements ClientSessionHolder {
    protected final String heartbeatRequest;
    protected final Duration heartbeatInterval;
    protected final int heartbeatMaxNoReply;

    protected final AtomicInteger outstandingHeartbeats = new AtomicInteger();

    /** Non-null only if using the &quot;keep-alive&quot; request mechanism */
    protected ScheduledFuture<?> clientHeartbeat;

    public ClientConnectionService(AbstractClientSession s) throws SshException {
        super(s);

        heartbeatRequest = CoreModuleProperties.HEARTBEAT_REQUEST.getRequired(this);
        heartbeatInterval = CoreModuleProperties.HEARTBEAT_INTERVAL.getRequired(this);
        heartbeatMaxNoReply = configureMaxNoReply();
    }

    protected int configureMaxNoReply() {
        @SuppressWarnings("deprecation")
        Duration timeout = CoreModuleProperties.HEARTBEAT_REPLY_WAIT.getOrNull(this);
        if (timeout == null || GenericUtils.isNegativeOrNull(heartbeatInterval) || GenericUtils.isEmpty(heartbeatRequest)) {
            return CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getRequired(this).intValue();
        }
        // The deprecated timeout is configured explicitly. If the new no-reply-max is _not_ explicitly configured,
        // set it from the timeout.
        Integer noReplyValue = CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getOrNull(this);
        if (noReplyValue != null) {
            return noReplyValue.intValue();
        }
        if (GenericUtils.isNegativeOrNull(timeout)) {
            return 0;
        }
        if (timeout.compareTo(heartbeatInterval) >= 0) {
            // Timeout is longer than the interval. With the previous system, that would have killed the session when
            // the timeout was reached. A slow server that managed to return the reply just before the timeout expired
            // would have delayed subsequent heartbeats. The new system will keep sending heartbeats with the given
            // interval. Thus we can have timeout / interval heartbeats without reply if we want to approximate the
            // timeout.
            double timeoutSec = timeout.getSeconds() + (timeout.getNano() / 1_000_000_000.0);
            double intervalSec = heartbeatInterval.getSeconds() + (heartbeatInterval.getNano() / 1_000_000_000.0);
            double multiple = timeoutSec / intervalSec;
            if (multiple >= Integer.MAX_VALUE - 1) {
                return Integer.MAX_VALUE;
            } else {
                return (int) multiple + 1;
            }
        }
        // Timeout is smaller than the interval. We want to have every heartbeat replied to.
        return 1;
        // This is an approximation. If no reply is forthcoming, the session will be killed after the interval. In the
        // previous system, it would have been killed after the timeout. We _could_ code something to schedule a task
        // that kills the session after the timeout and cancel that if we get a reply, but it seems a bit pointless.
    }

    @Override
    public final ClientSession getClientSession() {
        return getSession();
    }

    @Override // co-variant return
    public AbstractClientSession getSession() {
        return (AbstractClientSession) super.getSession();
    }

    @Override
    public void start() {
        ClientSession session = getClientSession();
        if (!session.isAuthenticated()) {
            throw new IllegalStateException("Session is not authenticated");
        }
        super.start();
    }

    @Override
    protected synchronized ScheduledFuture<?> startHeartBeat() {
        if (!GenericUtils.isNegativeOrNull(heartbeatInterval) && GenericUtils.isNotEmpty(heartbeatRequest)) {
            stopHeartBeat();

            outstandingHeartbeats.set(0);
            ClientSession session = getClientSession();
            FactoryManager manager = session.getFactoryManager();
            ScheduledExecutorService service = manager.getScheduledExecutorService();
            clientHeartbeat = service.scheduleAtFixedRate(
                    this::sendHeartBeat, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled()) {
                log.debug("startHeartbeat({}) - started at interval={} with request={}",
                        session, heartbeatInterval, heartbeatRequest);
            }

            return clientHeartbeat;
        } else {
            return super.startHeartBeat();
        }
    }

    @Override
    protected synchronized void stopHeartBeat() {
        try {
            super.stopHeartBeat();
        } finally {
            // No need to cancel since this is the same reference as the superclass heartbeat future
            if (clientHeartbeat != null) {
                clientHeartbeat = null;
            }
        }
    }

    @Override
    protected boolean sendHeartBeat() {
        if (clientHeartbeat == null) {
            return super.sendHeartBeat();
        }

        Session session = getSession();
        if (session.getKexState() != KexState.DONE) {
            // During KEX, global requests are delayed until after the key exchange is over. Don't count during KEX,
            // otherwise a slow KEX might cause us to kill the session prematurely.
            return false;
        }
        try {
            heartbeatCount.incrementAndGet();
            boolean withReply = heartbeatMaxNoReply > 0;
            int outstanding = outstandingHeartbeats.incrementAndGet();
            if (withReply && heartbeatMaxNoReply < outstanding) {
                throw new SshException("Got " + (outstanding - 1) + " heartbeat requests without reply");
            }
            Buffer buf = session.createBuffer(
                    SshConstants.SSH_MSG_GLOBAL_REQUEST, heartbeatRequest.length() + Byte.SIZE);
            buf.putString(heartbeatRequest);
            buf.putBoolean(withReply);

            // Even if we want a reply, we don't wait.
            if (withReply) {
                GlobalRequestFuture future = session.request(buf, heartbeatRequest, (cmd, buffer) -> {
                    // We got something back. Don't care about success or failure. (In particular we may get here in
                    // case the server responds SSH_MSG_UNIMPLEMENTED.)
                    outstandingHeartbeats.set(0);
                });
                future.addListener(this::futureDone);
            } else {
                IoWriteFuture future = session.writePacket(buf);
                future.addListener(this::futureDone);
            }
            return true;
        } catch (IOException | RuntimeException | Error e) {
            session.exceptionCaught(e);
            warn("sendHeartBeat({}) failed ({}) to send heartbeat #{} request={}: {}",
                    session, e.getClass().getSimpleName(), heartbeatCount, heartbeatRequest, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public AgentForwardSupport getAgentForwardSupport() {
        throw new IllegalStateException("Server side operation");
    }

    @Override
    public X11ForwardSupport getX11ForwardSupport() {
        throw new IllegalStateException("Server side operation");
    }
}
