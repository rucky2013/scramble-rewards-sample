package lk.lab.rewards;

import java.util.Set;

import com.google.gson.JsonParser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import lk.lab.rewards.redis.JedisHelper;
import redis.clients.jedis.Jedis;

public class ScramblePacksHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
	
	private static JsonParser jsonParser = new JsonParser();
	private Jedis jedis;

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request)
			throws Exception {
		messageReceived(ctx, request);
	}
	
	public void messageReceived(ChannelHandlerContext ctx, FullHttpRequest request)
			throws Exception {
		if (!request.getDecoderResult().isSuccess()) {
			sendError(ctx, HttpResponseStatus.BAD_REQUEST);
			return;
		}
		
		ByteBuf buf = request.content();
		byte[] reqByte = new byte[buf.readableBytes()];
		buf.readBytes(reqByte);
		String reqJson = new String(reqByte, "UTF-8");
		
		String user = jsonParser.parse(reqJson)
				.getAsJsonObject().get("user").getAsString();
		
		jedis = getJedis();
		
		try {
			// 获取user是否已领取
			Set<String> userKeys = JedisHelper.keys(jedis, user + ":*");
			if (userKeys.size() != 0) {
				FullHttpResponse response = buildResponse(
						user + " already got a pack.", HttpResponseStatus.OK);
				ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
				return;
			}

			// 获取一个红包ID
			Set<String> packKey = JedisHelper.zrange(jedis, "pack", 0, 0);
			if (packKey.size() == 0) {
				FullHttpResponse response = buildResponse(
						"all packs have been scrambled out.", HttpResponseStatus.OK);
				ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
				return;
			}

			// 检查是否已被领取
			String pack = packKey.toArray(new String[packKey.size()])[0];
			Set<String> packKeys = JedisHelper.keys(jedis, "*:" + pack);
			if (packKeys.size() != 0) {
				JedisHelper.zrem(jedis, "pack", pack);
				FullHttpResponse response = buildResponse(
						pack + " already been consumed.", HttpResponseStatus.OK);
				ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
				return;
			}

			// 领取红包
			long remCnt = JedisHelper.zrem(jedis, "pack", pack);
			if (remCnt == 0L) {
				FullHttpResponse response = buildResponse(
						pack + " already been consumed...", HttpResponseStatus.OK);
				ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
				return;
			}
			
			JedisHelper.set(jedis, user + ":" + pack,
					String.valueOf(System.currentTimeMillis()));

			// 结束
			FullHttpResponse response = buildResponse(user + " processed",
					HttpResponseStatus.OK);
			ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
		} finally {
			jedis.close();
		}
	}
	
	protected Jedis getJedis() {
		if (jedis == null) {
			return JedisHelper.getJedisFromPool();
		}
		
		return jedis;
	}
	
	private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
		FullHttpResponse response = buildResponse("Error!", HttpResponseStatus.BAD_REQUEST);
		ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
	}
	
	private FullHttpResponse buildResponse(String content, HttpResponseStatus sts) {
		FullHttpResponse response = new DefaultFullHttpResponse(
				HttpVersion.HTTP_1_1, sts,
				Unpooled.copiedBuffer(content, CharsetUtil.UTF_8));
		response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=UTF-8");
		return response;
	}
	
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
			throws Exception {
		ctx.close();
		cause.printStackTrace();
	}

}
