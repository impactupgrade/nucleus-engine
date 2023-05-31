package com.impactupgrade.nucleus.client;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;

import com.messagebird.MessageBirdClient;
import com.messagebird.MessageBirdService;
import com.messagebird.MessageBirdServiceImpl;
import com.messagebird.objects.Message;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.MediaType;

import static com.impactupgrade.nucleus.util.HttpClient.post;

public class MessageBirdSMSClient {
  private static final Logger log = LogManager.getLogger(MessageBirdSMSClient.class);
  private String accessKey;
  private String smsWorkspaceId;
  private String smsChannelId;
  protected Environment env;
  private MessageBirdService messageBirdService;
  private MessageBirdClient messageBirdClient;
  private String MESSAGE_BIRD_ENGAGEMENT_API_URL = "https://nest.messagebird.com/";

  public MessageBirdSMSClient(Environment env) {
    this.env = env;
    this.accessKey = env.getConfig().messageBird.accessKey;
    this.smsWorkspaceId = env.getConfig().messageBird.SMSWorkspaceId;
    this.smsChannelId =  env.getConfig().messageBird.SMSChannelId;
    this.messageBirdService = new MessageBirdServiceImpl(accessKey);
    this.messageBirdClient = new MessageBirdClient(messageBirdService);
  }

  // using the standard API & Java SDK
  public void sendMessage(String to, String from, String body) {
    try {
      Message message = new Message(from, body, to);
      messageBirdClient.sendMessage(message);
      log.info("Message sent successfully to " + to);
    } catch (Exception e) {
      log.error("Error sending message: " + e.getMessage());
    }
  }

  // using the new engagement platform direct API call
  public Response sendMessageEngagement(String to, String from, String body) {
    return post(
            MESSAGE_BIRD_ENGAGEMENT_API_URL + "/workspaces/" + smsWorkspaceId + "/channels/"+ smsChannelId +"/messages",
            createSMSMessageBody(to, body),
            MediaType.APPLICATION_JSON,
            headers()
    );
  }

  private HttpClient.HeaderBuilder headers(){
    return HttpClient.HeaderBuilder.builder().header("Authorization","AccessKey " + accessKey);
  }

  private String createSMSMessageBody(String to, String body) {
    return "{\"receiver\": {\"contacts\": [{\"identifierValue\": \"" + to + "\"}]}, " +
            "\"body\": {\"type\": \"text\", \"text\": {\"text\": \"" + body + "\"}}}";
  }
}


