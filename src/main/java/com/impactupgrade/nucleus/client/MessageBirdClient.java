package com.impactupgrade.nucleus.client;

import com.impactupgrade.nucleus.environment.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MessageBirdClient {
  private static final Logger log = LogManager.getLogger(MessageBirdClient.class);

  private static final String MESSAGE_BIRD_API_URL = "https://nest.messagebird.com/";

  private String accessKey;

  protected Environment env;

  public MessageBirdClient(Environment env){
    this.env = env;
    this.accessKey = env.getConfig().messageBird.accesKey;
  }

  
}
