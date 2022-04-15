package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import org.apache.commons.collections.MapUtils;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public interface AccountingPlatformService<C, T> extends SegmentService {

    List<T> getTransactions(Date startDate) throws Exception;

    List<T> createTransactions(List<PaymentGatewayEvent> transactions,
                               Map<String, C> contactsByPrimaryKey,
                               Map<String, C> contactsBySecondaryKey) throws Exception;

    List<C> getContacts() throws Exception;

    List<C> createContacts(List<CrmContact> crmContacts) throws Exception;

    String getTransactionKey(T transaction);

    String getContactSecondaryKey(C contact);

    String getContactPrimaryKey(C contact);

    String getCrmContactPrimaryKey(CrmContact crmContact);

    String getCrmContactSecondaryKey(CrmContact crmContact);

    default C getContactForCrmContact(CrmContact crmContact,
                                      Map<String, C> contactsByPrimaryKey,
                                      Map<String, C> contactsBySecondaryKey) {
        if (Objects.isNull(crmContact)
                || (MapUtils.isEmpty(contactsByPrimaryKey) && MapUtils.isEmpty(contactsBySecondaryKey))) {
            return null;
        }

        C contact;

        String crmContactPrimaryKey = getCrmContactPrimaryKey(crmContact).toLowerCase(Locale.ROOT);
        contact = contactsByPrimaryKey.get(crmContactPrimaryKey);

        if (Objects.isNull(contact)) {
            String crmContactSecondaryKey = getCrmContactSecondaryKey(crmContact).toLowerCase(Locale.ROOT);
            contact = contactsBySecondaryKey.get(crmContactSecondaryKey);
        }

        return contact;
    }

}
