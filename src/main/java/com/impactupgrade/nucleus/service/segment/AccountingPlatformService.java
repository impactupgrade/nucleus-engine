package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import org.apache.commons.collections.MapUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public interface AccountingPlatformService<C, T> extends SegmentService {

    List<T> getTransactions(Date startDate) throws Exception;

    Function<T, String> getTransactionKeyFunction();

    List<C> getContacts() throws Exception;

    List<C> createContacts(List<CrmContact> crmContacts) throws Exception;

    Function<CrmContact, String> getCrmContactPrimaryKeyFunction();

    Function<C, String> getContactPrimaryKeyFunction();

    Function<CrmContact, String> getCrmContactSecondaryKeyFunction();

    Function<C, String> getContactSecondaryKeyFunction();

    List<T> createTransactions(List<PaymentGatewayEvent> transactions,
                               Map<String, C> contactsByPrimaryKey,
                               Map<String, C> contactsBySecondaryKey) throws Exception;

    default C getContactForCrmContact(CrmContact crmContact,
                                      Map<String, C> contactsByPrimaryKey,
                                      Map<String, C> contactsBySecondaryKey) {
        if (Objects.isNull(crmContact)
                || (MapUtils.isEmpty(contactsByPrimaryKey) && MapUtils.isEmpty(contactsBySecondaryKey))) {
            return null;
        }

        C contact = null;
        Function<CrmContact, String> crmContactPrimaryKeyFunction = getCrmContactPrimaryKeyFunction();
        Function<CrmContact, String> crmContactSecondaryKeyFunction = getCrmContactSecondaryKeyFunction();
        if (Objects.nonNull(crmContactPrimaryKeyFunction)) {
            String crmContactPrimaryKey = crmContactPrimaryKeyFunction.apply(crmContact);
            contact = contactsByPrimaryKey.get(crmContactPrimaryKey);

            if (Objects.isNull(contact) && Objects.nonNull(crmContactSecondaryKeyFunction)) {
                String crmContactSecondaryKey = crmContactPrimaryKeyFunction.apply(crmContact);
                contact = contactsBySecondaryKey.get(crmContactSecondaryKey);
            }
        }

        return contact;
    }

}
