//package com.impactupgrade.nucleus.client;
//
//import com.ecwid.maleorang.MailchimpException;
//import com.ecwid.maleorang.method.v3_0.lists.members.DeletePermanentMemberMethod;
//import com.ecwid.maleorang.method.v3_0.lists.members.GetMembersMethod;
//import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
//import com.impactupgrade.sfdc.DRSFDCClient;
//import com.sforce.soap.enterprise.sobject.fnsf.Account;
//import com.sforce.soap.partner.sobject.SObject;
//import com.sforce.ws.ConnectionException;
//
//import java.io.IOException;
//import java.util.Date;
//import java.util.Optional;
//
//// TODO: Some sample code to get an MC client started. This was a little one-off, but reusable and could eventually
//// be a part of the new MC sync app.
//public class MailchimpClient {
//
//  // TODO: env var
//  private static final com.ecwid.maleorang.MailchimpClient CLIENT = new com.ecwid.maleorang.MailchimpClient("65302c33e410295ee4128e309a91aa54-us8");
//
//  private static final DRSFDCClient sfdcClient = new DRSFDCClient();
//
//  public void findSpamContacts(Date sinceDate) throws IOException, MailchimpException, ConnectionException, InterruptedException {
//    // TODO: 39dcb0514a == US Marketing
//    GetMembersMethod getMembersMethod = new GetMembersMethod("39dcb0514a");
//    // TODO: Max is 1000, but that timed out
//    getMembersMethod.count = 600;
//    getMembersMethod.since_timestamp_opt = sinceDate;
//    GetMembersMethod.Response getMembersResponse = CLIENT.execute(getMembersMethod);
//    int count = 0;
//    int size = getMembersResponse.members.size();
//    for (MemberInfo member : getMembersResponse.members) {
//      count++;
//      Optional<SObject> contact = sfdcClient.getContactByEmail(member.email_address);
//      Optional<Account> account = sfdcClient.getAccountByEmail(member.email_address);
//      if (contact.isEmpty() && account.isEmpty()) {
//        System.out.println("[" + count + " of " + size + "] missing : " + member.email_address);
//        // TODO: currently requires a branch build (https://github.com/brmeyer/maleorang/tree/permanently-delete)
//        // Have a PR in place, but the project may be abandoned. Time to fork it?
//        DeletePermanentMemberMethod deleteMemberMethod = new DeletePermanentMemberMethod("39dcb0514a", member.email_address);
//        CLIENT.execute(deleteMemberMethod);
//      } else {
//        System.out.println("[" + count + " of " + size + "]");
//      }
//    }
//  }
//
////  public static void main(String[] args) throws IOException, MailchimpException, ConnectionException, InterruptedException {
////    Calendar c = Calendar.getInstance();
////    c.set(Calendar.YEAR, 2020);
////    c.set(Calendar.MONTH, 11);
////    c.set(Calendar.DATE, 1);
////    c.set(Calendar.HOUR, 0);
////    c.set(Calendar.MINUTE, 0);
////    c.set(Calendar.SECOND, 0);
////    c.set(Calendar.MILLISECOND, 0);
////
////    new MailchimpClient().findSpamContacts(c.getTime());
////  }
//}