package ro.sellfluence.saga;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.CharsetUtil;
import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.support.UserPassword;

import static java.util.logging.Level.FINE;

public class ProxyHandler extends ChannelInboundHandlerAdapter {
    private static final UserPassword emagCredentials = UserPassword.findAlias("sellfusion");

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf in = (ByteBuf) msg;
        String request = in.toString(CharsetUtil.UTF_8);
        System.out.println("Received request: " + request);

        if (request.contains("/api-3/order/count HTTP/1.1")){
            EmagApi.setAPILogLevel(FINE);
            var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
            var response = emag.countOrderRequestRaw();
            ctx.writeAndFlush(response);
        } else {

        }
//       POST /api-3/order/count HTTP/1.1


        // Forward the request to the real endpoint
        // ...

        // Receive the response from the real endpoint
        // ...

        // Modify the response as needed
        // ...

        // Send the modified response back to the client
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}
