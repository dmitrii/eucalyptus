package com.eucalyptus.walrus.pipeline.stages;

import com.eucalyptus.walrus.pipeline.WalrusRESTExceptionHandler;
import org.jboss.netty.channel.ChannelPipeline;

import com.eucalyptus.ws.stages.UnrollableStage;

public class WalrusRESTExceptionStage implements UnrollableStage {

	@Override
	public int compareTo(UnrollableStage arg0) {
		return this.getName().compareTo(arg0.getName());
	}

	@Override
	public void unrollStage(ChannelPipeline pipeline) {
		pipeline.addLast("walrus-exception", new WalrusRESTExceptionHandler());
	}

	@Override
	public String getName() {
		return "walrus-exception";
	}

}
