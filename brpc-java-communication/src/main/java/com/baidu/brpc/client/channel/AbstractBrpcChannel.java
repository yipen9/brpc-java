/*
 * Copyright (C) 2019 Baidu, Inc. All Rights Reserved.
 */
package com.baidu.brpc.client.channel;

import com.baidu.brpc.RpcMethodInfo;
import com.baidu.brpc.client.CommunicationOptions;
import com.baidu.brpc.client.FastFutureStore;
import com.baidu.brpc.client.MethodUtils;
import com.baidu.brpc.client.RpcFuture;
import com.baidu.brpc.exceptions.RpcException;
import com.baidu.brpc.protocol.Protocol;
import com.baidu.brpc.protocol.RpcRequest;
import com.baidu.brpc.protocol.push.SPHead;
import com.baidu.brpc.protocol.push.ServerPushProtocol;
import com.baidu.brpc.server.push.RegisterService;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.net.InetSocketAddress;
import java.util.Queue;

@Slf4j
public abstract class AbstractBrpcChannel implements BrpcChannel {
    protected ServiceInstance serviceInstance;
    protected CommunicationOptions communicationOptions;
    protected Bootstrap bootstrap;
    protected BootstrapManager bootstrapManager = BootstrapManager.getInstance();

    public AbstractBrpcChannel(ServiceInstance serviceInstance,
                               CommunicationOptions communicationOptions) {
        this.serviceInstance = serviceInstance;
        this.communicationOptions = communicationOptions;
        this.bootstrap = bootstrapManager.getOrCreateBootstrap(
                serviceInstance.getServiceName(), communicationOptions);//创建客户端bootstrap
    }

    @Override
    public void updateChannel(Channel channel) {
    }

    // server push 模式下，  把client的clientName发送到server去
    public void sendClientNameToServer(ChannelFuture channelFuture) {
        RpcRequest r = new RpcRequest();
        r.setChannel(channelFuture.channel());
        r.setReadTimeoutMillis(10 * 1000);
        r.setWriteTimeoutMillis(10 * 1000);
        SPHead spHead = ((ServerPushProtocol) communicationOptions.getProtocol()).createSPHead();
        spHead.setType(SPHead.TYPE_REGISTER_REQUEST); // 注册类型
        r.setSpHead(spHead);

        String serviceName = RegisterService.class.getName();
        String methodName = "registerClient";
        r.setServiceName(serviceName);
        r.setMethodName(methodName);
        RpcMethodInfo rpcMethodInfo = MethodUtils.getRpcMethodInfo(RegisterService.class, methodName);
        r.setRpcMethodInfo(rpcMethodInfo);
        r.setArgs(new Object[] {communicationOptions.getClientName()});

        // generate correlationId
        RpcFuture registerRpcFuture = new RpcFuture();
        long correlationId = FastFutureStore.getInstance(0).put(registerRpcFuture);
        registerRpcFuture.setCorrelationId(correlationId);
        // rpcFuture.setChannelInfo(channelInfo);
        r.setCorrelationId(correlationId);

        ByteBuf byteBuf;
        try {
            log.debug("send sendClientNameToServer, name:{}, correlationId:{}",
                    communicationOptions.getClientName(), r.getCorrelationId());
            byteBuf = communicationOptions.getProtocol().encodeRequest(r);
        } catch (Exception e) {
            log.error("send report packet to server, encode packet failed, msg:", e);
            throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, "rpc encode failed:", e);
        }
        channelFuture.channel().writeAndFlush(byteBuf);
    }

    @Override
    public Channel connect() {
        final String ip = serviceInstance.getIp();
        final int port = serviceInstance.getPort();
        final ChannelFuture future = bootstrap.connect(new InetSocketAddress(ip, port));
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if (channelFuture.isSuccess()) {
                    log.debug("future callback, connect to {}:{} success, channel={}",
                            ip, port, channelFuture.channel());
                    // 发送clientName包到server
                    if (communicationOptions.getProtocol() instanceof ServerPushProtocol) {
                        sendClientNameToServer(future);
                    }
                } else {
                    log.debug("future callback, connect to {}:{} failed due to {}",
                            ip, port, channelFuture.cause().getMessage());
                }
            }
        });
        future.syncUninterruptibly();
        if (future.isSuccess()) {
            return future.channel();
        } else {
            // throw exception when connect failed to the connection pool acquirer
            log.error("connect to {}:{} failed, msg={}", ip, port, future.cause().getMessage());
            throw new RpcException(future.cause());
        }
    }

    @Override
    public ServiceInstance getServiceInstance() {
        return serviceInstance;
    }

    @Override
    public long getFailedNum() {
        return 0;
    }

    @Override
    public void incFailedNum() {
    }

    @Override
    public Queue<Integer> getLatencyWindow() {
        return null;
    }

    @Override
    public void updateLatency(int latency) {
    }

    @Override
    public void updateLatencyWithReadTimeOut() {
    }

    @Override
    public Protocol getProtocol() {
        return communicationOptions.getProtocol();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(serviceInstance.getIp())
                .append(serviceInstance.getPort())
                .toHashCode();
    }

    @Override
    public boolean equals(Object object) {
        boolean flag = false;
        if (object != null && BrpcChannel.class.isAssignableFrom(object.getClass())) {
            BrpcChannel rhs = (BrpcChannel) object;
            flag = new EqualsBuilder()
                    .append(serviceInstance.getIp(), rhs.getServiceInstance().getIp())
                    .append(serviceInstance.getPort(), rhs.getServiceInstance().getPort())
                    .isEquals();
        }
        return flag;
    }

    @Override
    public void close() {
    }
}
