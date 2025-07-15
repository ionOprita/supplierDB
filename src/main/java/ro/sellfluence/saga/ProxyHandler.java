package ro.sellfluence.saga;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.support.UserPassword;

import java.nio.charset.StandardCharsets;

import static java.util.logging.Level.FINE;

public class ProxyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final UserPassword emagCredentials = UserPassword.findAlias("sellfusion");

    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        String requestURI = msg.uri();
        if (requestURI.contains("api-3/order/count HTTP/1.1")) {
            EmagApi.setAPILogLevel(FINE);
            var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
            var requestBody = msg.content().toString(StandardCharsets.UTF_8);
            var responseBody = emag.countOrderRequestRaw(requestBody);
            if (responseBody != null) {
                // Create a response
                FullHttpResponse httpResponse = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.wrappedBuffer(responseBody.getBytes())
                );
                // Set the Content-Type header
                httpResponse.headers().set("Content-Type", "application/json");
                // Set the Content-Length header
                httpResponse.headers().set("Content-Length", responseBody.length());

                // Write the response back to the client
                ctx.writeAndFlush(httpResponse).addListener(future -> {
                    if (future.isSuccess()) {
                        System.out.println("Response sent successfully.");
                    } else {
                        System.err.println("Failed to send response: " + future.cause());
                    }
                });
            }
        } else {
            System.out.println("Received request: "+msg);
        }
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