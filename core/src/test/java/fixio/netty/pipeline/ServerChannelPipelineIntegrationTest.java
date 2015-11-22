/*
 * Copyright 2014 The FIX.io Project
 *
 * The FIX.io Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package fixio.netty.pipeline;

import fixio.events.LogonEvent;
import fixio.fixprotocol.*;
import fixio.handlers.FixApplicationAdapter;
import fixio.netty.pipeline.server.FixAcceptorChannelInitializer;
import fixio.netty.pipeline.server.FixAuthenticator;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import static org.apache.commons.lang3.RandomStringUtils.randomAscii;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ServerChannelPipelineIntegrationTest {

    @Mock
    private FixAuthenticator authenticator;
    private EmbeddedChannel embeddedChannel;
    private volatile LogonEvent logonEvent;

    @Before
    public void setUp() throws Exception {

        //address = LocalAddress.ANY;
        embeddedChannel = new EmbeddedChannel() {
            @Override
            public ChannelFuture write(Object msg) {
                return super.write(msg);
            }

            @Override
            public ChannelFuture writeAndFlush(Object msg) {
                outboundMessages().add(msg);
                return super.writeAndFlush(msg);
            }
        };

        EventLoopGroup workerGroup = new NioEventLoopGroup();
        FixApplicationAdapter fixApplicationAdapter = new FixApplicationAdapter() {
            @Override
            public void onLogon(ChannelHandlerContext ctx, LogonEvent msg) {
                logonEvent = msg;
            }
        };
        final FixAcceptorChannelInitializer<Channel> channelInitializer = new FixAcceptorChannelInitializer<>(
                workerGroup,
                fixApplicationAdapter,
                authenticator,
                new InMemorySessionRepository()
        );

//        channelInitializer.initChannel(ch);

        ChannelPipeline pipeline = embeddedChannel.pipeline();
        pipeline.removeFirst();
        pipeline.addFirst(new ChannelOutboundHandlerAdapter() {


            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                EmbeddedChannel embeddedChannel = (EmbeddedChannel) ctx.channel();
                embeddedChannel.outboundMessages().add(msg);
                super.write(ctx, msg, promise);
            }
        });
        channelInitializer.initChannel(embeddedChannel);

        when(authenticator.authenticate(any(FixMessage.class))).thenReturn(true);
    }

    @Test
    public void test01ProcessLogonSuccess() {
        final FixMessageBuilderImpl logon = createLogonMessage();

        assertThat(embeddedChannel.isOpen(), is(true));

        embeddedChannel.writeInbound(logon);

        verify(authenticator).authenticate(logon);
        assertThat("channel open", embeddedChannel.isOpen(), is(true));
        assertThat("logonEvent",logonEvent, notNullValue());
    }

    @Test
    public void test02ProcessTestRequest() {
        final FixMessageBuilderImpl logon = createLogonMessage();

        final FixMessageBuilderImpl testRequest = new FixMessageBuilderImpl(MessageTypes.TEST_REQUEST);
        final FixMessageHeader header = testRequest.getHeader();
        header.setSenderCompID(logon.getSenderCompID());
        header.setTargetCompID(logon.getTargetCompID());
        header.setMsgSeqNum(2);

        testRequest.add(FieldType.TestReqID, randomAscii(5));

        embeddedChannel.writeInbound(logon, testRequest);

        final Object o = embeddedChannel.readOutbound();
        assertThat(o, CoreMatchers.instanceOf(FixMessage.class));
        assertThat(((FixMessage) o).getMessageType(), is(MessageTypes.HEARTBEAT));
    }

    private FixMessageBuilderImpl createLogonMessage() {
        final FixMessageBuilderImpl logon = new FixMessageBuilderImpl(MessageTypes.LOGON);
        final FixMessageHeader header = logon.getHeader();
        header.setSenderCompID(randomAscii(3));
        header.setTargetCompID(randomAscii(4));
        header.setMsgSeqNum(1);
        return logon;
    }
}
