package com.continuuity.gateway.accessor;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.continuuity.api.data.OperationContext;
import com.continuuity.common.conf.CConfiguration;
import com.continuuity.data.DataFabricImpl;
import com.continuuity.gateway.Accessor;
import com.continuuity.gateway.util.HttpConfig;
import com.continuuity.gateway.util.NettyHttpPipelineFactory;
import com.continuuity.gateway.util.NettyRequestHandlerFactory;

public class QueryRestAccessor
extends Accessor implements NettyRequestHandlerFactory {

  private static final Logger LOG = LoggerFactory
      .getLogger(QueryRestAccessor.class);

  /**
   * this will provide defaults for the HTTP service, such as port and paths
   */
  private static final HttpConfig defaultHttpConfig =
      new HttpConfig("accessor.query")
          .setPort(10010)
          .setPathMiddle("/rest-query/");

  /**
   * this will provide the actual HTTP configuration, backed by the default
   */
  private HttpConfig httpConfig = defaultHttpConfig;

  /**
   * return the HTTP configuration for this accessor
   *
   * @return the HTTP configuration
   */
  public HttpConfig getHttpConfig() {
    return this.httpConfig;
  }

  /**
   * this is the active Netty server channel
   */
  private Channel serverChannel;

  @Override
  public void configure(CConfiguration configuration) throws Exception {
    super.configure(configuration);
    this.httpConfig = HttpConfig.configure(
        this.name, configuration, defaultHttpConfig);
  }

  @Override
  public SimpleChannelUpstreamHandler newHandler() {
    return null;
    //return new QueryRestHandler(this, new PayvmentQueryRestProvider(
    //    new DataFabricImpl(super.getExecutor(), OperationContext.DEFAULT)));
  }

  @Override
  public void start() throws Exception {
    LOG.debug("Starting up " + this);
    // construct the internet address
    InetSocketAddress address =
        new InetSocketAddress(this.httpConfig.getPort());
    try {
      // create a server bootstrap
      ServerBootstrap bootstrap = new ServerBootstrap(
          new NioServerSocketChannelFactory(
              Executors.newCachedThreadPool(),
              Executors.newCachedThreadPool(),
              this.httpConfig.getThreads()));
      // and use a pipeline factory that uses this to cnfigure itself
      // and to create a request handler for each client request.
      bootstrap.setPipelineFactory(
          new NettyHttpPipelineFactory(this.httpConfig, this));
      // bind to the address = start the service
      this.serverChannel = bootstrap.bind(address);
      // server is now running
    } catch (Exception e) {
      LOG.error("Failed to startup accessor '" + this.getName()
          + "' at " + this.httpConfig.getBaseUrl() + ".");
      throw e;
    }
    LOG.info("Connector " + this.getName() + " now running" +
        " at " + this.httpConfig.getBaseUrl() +
        " with " + this.httpConfig.getThreads() + " threads.");
  }

  @Override
  public void stop() {
    LOG.debug("Stopping " + this);
    // closing the channel stops the service
    this.serverChannel.close();
    LOG.debug("Stopped " + this);
  }
}
