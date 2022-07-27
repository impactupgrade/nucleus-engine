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
    MessageCreator messageCreator = Message.creator(
            new PhoneNumber(to),
            new PhoneNumber(from),
            body
    );
    if (!Strings.isNullOrEmpty(callbackUrl)) {
      messageCreator.setStatusCallback(callbackUrl);
    }
    return messageCreator.create(restClient);
  }

  public Conversation createConversation(String friendlyName) {
    return Conversation.creator().setFriendlyName(friendlyName).create(restClient);
  }

  public Participant createConversationProxyParticipant(String conversationSid, String identity) {
    return Participant.creator(conversationSid).setIdentity(identity).create(restClient);
  }

  public Participant createConversationProjectedParticipant(String conversationSid, String identity, String projectedAddress) {
    return Participant.creator(conversationSid).setIdentity(identity).setMessagingBindingProjectedAddress(projectedAddress).create(restClient);
  }

  public Participant createConversationExternalParticipant(String conversationSid, String bindingAddress) {
    return Participant.creator(conversationSid).setMessagingBindingAddress(bindingAddress).create(restClient);
  }

  public Participant updateConversationParticipant(String conversationSid, String participantSid, String attributes) {
    return Participant.updater(conversationSid, participantSid).setAttributes(attributes).update(restClient);
  }

  public Conversation fetchConversation(String conversationSid) {
    return Conversation.fetcher(conversationSid).fetch(restClient);
  }

  public Optional<Conversation> findConversation(String friendlyName) {
    // TODO: Is there not a search or by-friendly-name?
    ResourceSet<Conversation> conversations = Conversation.reader().read(restClient).setAutoPaging(true);
    for (Conversation conversation : conversations) {
      if (conversation.getState() == Conversation.State.ACTIVE && conversation.getFriendlyName() != null && conversation.getFriendlyName().equalsIgnoreCase(friendlyName)) {
        return Optional.of(conversation);
      }
    }
    return Optional.empty();
  }

  public List<Conversation> allConversations() {
    List<Conversation> all = new ArrayList<>();
    ResourceSet<Conversation> conversations = Conversation.reader().read(restClient).setAutoPaging(true);
    for (Conversation conversation : conversations) {
      all.add(conversation);
    }
    return all;
  }

  public Participant fetchConversationParticipant(String conversationSid, String participantSid) {
    return Participant.fetcher(conversationSid, participantSid).fetch(restClient);
  }

  public List<Participant> allConversationParticipants(String conversationSid) {
    List<Participant> all = new ArrayList<>();
    ResourceSet<Participant> participants = Participant.reader(conversationSid).read(restClient).setAutoPaging(true);
    for (Participant participant : participants) {
      all.add(participant);
    }
    return all;
  }

  public void deleteConversation(String conversationSid) {
    Conversation.deleter(conversationSid).delete(restClient);
  }
}
