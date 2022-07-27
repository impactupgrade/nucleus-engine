package com.impactupgrade.nucleus.model;

import java.util.List;

public class CrmActivity {

    public String id;
    public String targetId;
    public String assignTo; // User ID

    public CrmActivity.Type type;
    public String conversationId;

    public List<String> messageIds;

    public enum Type {
        SMS_CONVERSATION,
        EMAIL;
    }

}
