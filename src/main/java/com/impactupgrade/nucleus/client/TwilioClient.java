package com.impactupgrade.nucleus.client;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.twilio.base.ResourceSet;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.rest.conversations.v1.Conversation;
import com.twilio.rest.conversations.v1.conversation.Participant;
import com.twilio.type.PhoneNumber;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Most of Nucleus' integration with Twilio is responding to webhooks. But some cases require outbound API usage.
 * Twilio's Java SDK is really easy to work with, but we encapsulate some of it here to remove boilerplate.
 */
public class TwilioClient {

  private final Environment env;
  private final TwilioRestClient restClient;

  public TwilioClient(Environment env) {
    this.env = env;

    restClient = new TwilioRestClient.Builder(
        env.getConfig().twilio.publicKey,
        env.getConfig().twilio.secretKey
    ).build();
  }

  public Message sendMessage(String to, String body) {
    return sendMessage(to, env.getConfig().twilio.senderPn, body, null);
  }

  public Message sendMessage(String to, String body, String callbackUrl) {
    return sendMessage(to, env.getConfig().twilio.senderPn, body, callbackUrl);
  }

  public Message sendMessage(String to, String from, String body, String callbackUrl) {
    return sendMessage(to, from, body, null, callbackUrl);
  }

  public Message sendMessage(String to, String from, String body, String mediaUrl, String callbackUrl) {
    MessageCreator messageCreator = Message.creator(
        new PhoneNumber(to),
        new PhoneNumber(from),
        body
    );
    if (!Strings.isNullOrEmpty(mediaUrl)) {
      messageCreator.setMediaUrl(mediaUrl);
    }
    if (!Strings.isNullOrEmpty(callbackUrl)) {
      messageCreator.setStatusCallback(callbackUrl);
    }
    return messageCreator.create(restClient);
  }
}
