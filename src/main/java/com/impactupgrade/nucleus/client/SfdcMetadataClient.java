/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.google.common.collect.Lists;
import com.impactupgrade.nucleus.environment.Environment;
import com.sforce.soap.metadata.Connector;
import com.sforce.soap.metadata.CustomField;
import com.sforce.soap.metadata.CustomValue;
import com.sforce.soap.metadata.FieldType;
import com.sforce.soap.metadata.FileProperties;
import com.sforce.soap.metadata.GlobalValueSet;
import com.sforce.soap.metadata.Layout;
import com.sforce.soap.metadata.LayoutColumn;
import com.sforce.soap.metadata.LayoutItem;
import com.sforce.soap.metadata.LayoutSection;
import com.sforce.soap.metadata.LayoutSectionStyle;
import com.sforce.soap.metadata.ListMetadataQuery;
import com.sforce.soap.metadata.Metadata;
import com.sforce.soap.metadata.MetadataConnection;
import com.sforce.soap.metadata.PicklistValue;
import com.sforce.soap.metadata.Profile;
import com.sforce.soap.metadata.ProfileFieldLevelSecurity;
import com.sforce.soap.metadata.ReadResult;
import com.sforce.soap.metadata.RecordType;
import com.sforce.soap.metadata.RecordTypePicklistValue;
import com.sforce.soap.metadata.SaveResult;
import com.sforce.soap.metadata.UiBehavior;
import com.sforce.soap.metadata.ValueSet;
import com.sforce.soap.metadata.ValueSetValuesDefinition;
import com.sforce.soap.partner.LoginResult;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.units.qual.C;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Wraps the SFDC Metadata SOAP API.
 */
public class SfdcMetadataClient {

  private static final Logger log = LogManager.getLogger(SfdcMetadataClient.class.getName());

  protected final Environment env;
  private final String username;
  private final String password;

  // Keep it simple and build on-demand, since this is rarely used! But if caching is needed, see the
  // approach in SFDCPartnerAPIClient.
  public MetadataConnection metadataConn() throws ConnectionException {
    ConnectorConfig metadataConfig = new ConnectorConfig();
    metadataConfig.setUsername(username);
    metadataConfig.setPassword(password);
    // Oh, Salesforce. We must call the login endpoint to obtain the metadataServerUrl and sessionId.
    // No idea why this isn't generated as a part of the connection, like Enterprise WSDL does it...
    LoginResult loginResult = env.sfdcClient(username, password).login();
    metadataConfig.setServiceEndpoint(loginResult.getMetadataServerUrl());
    metadataConfig.setSessionId(loginResult.getSessionId());
    return Connector.newConnection(metadataConfig);
  }

  /**
   * We need to support logging in on an as-needed basis, rather than always using a single user. QBWC sends a specific
   * SFDC username/password, and foreseeably we'll need a similar concept for other integration points.
   *
   * @param username
   * @param password
   */
  public SfdcMetadataClient(Environment env, String username, String password) {
    this.env = env;
    this.username = username;
    this.password = password;
  }

  /**
   * Log in using the default account.
   */
  public SfdcMetadataClient(Environment env) {
    this.env = env;
    this.username = env.getConfig().salesforce.username;
    this.password = env.getConfig().salesforce.password;
  }

  /**
   * For updating C1's form data provider object
   * @param globalPicklistApiName
   * @param values
   * @throws ConnectionException
   */
  public void updatePicklist(String globalPicklistApiName, List<String> values) throws ConnectionException {
    log.info("updating values of {}", globalPicklistApiName);

    //get the existing picklist
    MetadataConnection metadataConn = metadataConn();
    List<CustomValue> customValues = new ArrayList<>();
    ReadResult globalValueSetResult = metadataConn.readMetadata(GlobalValueSet.class.getSimpleName(), new String[]{globalPicklistApiName});
    GlobalValueSet globalValueSet = (GlobalValueSet) globalValueSetResult.getRecords()[0];

    //creates the list of custom values
    for(String value : values){
      CustomValue customValue = new CustomValue();
      customValue.setDefault(false);
      customValue.setFullName(value);
      customValue.setIsActive(true);
      customValue.setLabel(value);

      customValues.add(customValue);
    }
    // sort alphabetically and convert back to an array
    globalValueSet.setCustomValue(
            customValues.stream().sorted(Comparator.comparing(CustomValue::getLabel)).toArray(CustomValue[]::new)
    );
    // update the global picklist
    Arrays.stream(metadataConn.updateMetadata(new Metadata[]{globalValueSet})).forEach(log::info);

  }

  /**
   * For new picklist values, update the global picklist, then iterate over all
   * contact/campaign/opportunity record types and add the value to those picklists. The global picklist only
   * provides the *possible* values, while record types much individually select the values they want to include.
   *
   * @param newValue
   * @throws ConnectionException
   */
  public void addValueToPicklist(String globalPicklistApiName, String newValue, List<String> recordTypeFieldApiNames) throws ConnectionException {
    log.info("adding {} {}", globalPicklistApiName, newValue);

    MetadataConnection metadataConn = metadataConn();

    // fetch the picklist (called a GlobalValueSet in the API)
    ReadResult globalValueSetResult = metadataConn.readMetadata(GlobalValueSet.class.getSimpleName(), new String[]{globalPicklistApiName});
    GlobalValueSet globalValueSet = (GlobalValueSet) globalValueSetResult.getRecords()[0];
    List<CustomValue> customValues = new ArrayList<>();
    Collections.addAll(customValues, globalValueSet.getCustomValue());

    // add the new value
    CustomValue customValue = new CustomValue();
    customValue.setDefault(false);
    customValue.setFullName(newValue);
    // this *seems* to be the magic sauce that automatically adds the new value to *all* RecordType selected values,
    // rather than having to loop over the Contact/Campaign/Donation/etc RecordTypes and select it on each
    customValue.setIsActive(true);
    customValue.setLabel(newValue);
    customValues.add(customValue);

    // sort alphabetically and convert back to an array
    globalValueSet.setCustomValue(
        customValues.stream().sorted(Comparator.comparing(CustomValue::getLabel)).toArray(CustomValue[]::new)
    );

    // update the global picklist
    Arrays.stream(metadataConn.updateMetadata(new Metadata[]{globalValueSet})).forEach(log::info);

    log.info("added {} {} to GlobalValueSet {}", globalPicklistApiName, newValue, globalValueSet.getFullName());

    log.info("adding {} {} to all record types", globalPicklistApiName, newValue);

    // dynamically find all RecordTypes for each type of Object, then add the new value to each RecordType's list
    ListMetadataQuery listMetadataQuery = new ListMetadataQuery();
    listMetadataQuery.setType("RecordType");
    // get all record types
    for (FileProperties fileProperties : metadataConn.listMetadata(new ListMetadataQuery[]{listMetadataQuery}, 0.0)) {
      // filter down to the record types we care about
      if ("objects/Contact.object".equalsIgnoreCase(fileProperties.getFileName())
          || "objects/Campaign.object".equalsIgnoreCase(fileProperties.getFileName())
          || "objects/Opportunity.object".equalsIgnoreCase(fileProperties.getFileName())
          || "objects/npe03__Recurring_Donation__c.object".equalsIgnoreCase(fileProperties.getFileName())) {
        for (Metadata metadata : metadataConn.readMetadata(RecordType.class.getSimpleName(), new String[]{fileProperties.getFullName()}).getRecords()) {
          RecordType recordType = (RecordType) metadata;
          log.info("checking record type record type {}/{} for the {} picklist", fileProperties.getFileName(), recordType.getFullName(), globalPicklistApiName);
          // find the picklist
          for (RecordTypePicklistValue picklist : recordType.getPicklistValues()) {
            // important to use contains and not equals here, as the name isn't 100% consistent across the RecordTypes
            if (recordTypeFieldApiNames.stream().anyMatch(s -> picklist.getPicklist().contains(s))) {
              if (Arrays.stream(picklist.getValues()).anyMatch(value -> value.getFullName().equals(newValue))) {
                log.info("{} {} already in record type {}/{}; skipping...", globalPicklistApiName, newValue, fileProperties.getFileName(), recordType.getFullName());
              } else {
                // add the new value
                List<PicklistValue> picklistValues = new ArrayList<>();
                Collections.addAll(picklistValues, picklist.getValues());
                PicklistValue picklistValue = new PicklistValue();
                picklistValue.setFullName(newValue);
                picklistValues.add(picklistValue);
                picklist.setValues(picklistValues.toArray(new PicklistValue[0]));

                // update the record type, but only if we actually added something
                Arrays.stream(metadataConn.updateMetadata(new Metadata[]{recordType})).forEach(log::info);

                log.info("added {} {} to record type {}/{}", globalPicklistApiName, newValue, fileProperties.getFileName(), recordType.getFullName());

                break;
              }
            }
          }
        }
      }
    }

    log.info("added {} {} to all contact/campaign/opportunity record types", globalPicklistApiName, newValue);
  }

  // TODO: createCustomField methods are getting out of control -- shift to a builder pattern?

  public void createCustomField(String objectName, String fieldName, String fieldLabel, FieldType fieldType)
      throws ConnectionException {
    createCustomField(objectName, fieldName, fieldLabel, fieldType, null, null, null, null, null);
  }

  public void createCustomField(String objectName, String fieldName, String fieldLabel, FieldType fieldType,
      Integer fieldLength) throws ConnectionException {
    createCustomField(objectName, fieldName, fieldLabel, fieldType, fieldLength, null, null, null, null);
  }

  public void createCustomField(String objectName, String fieldName, String fieldLabel, FieldType fieldType,
      Integer fieldPrecision, Integer fieldScale) throws ConnectionException {
    createCustomField(objectName, fieldName, fieldLabel, fieldType, null, fieldPrecision, fieldScale, null, null);
  }

  public void createCustomField(String objectName, String fieldName, String fieldLabel, FieldType fieldType, List<String> values)
      throws ConnectionException {
    createCustomField(objectName, fieldName, fieldLabel, fieldType, null, null, null, values, null);
  }

  public void createCustomField(String objectName, String fieldName, String fieldLabel, FieldType fieldType, String globalPicklistName)
      throws ConnectionException {
    createCustomField(objectName, fieldName, fieldLabel, fieldType, null, null, null, null, globalPicklistName);
  }

  // cache it
  private FileProperties[] profilesMetadata = null;

  public void createCustomField(String objectName, String fieldName, String fieldLabel, FieldType fieldType,
			Integer fieldLength, Integer fieldPrecision, Integer fieldScale, List<String> values, String globalPicklistName) throws ConnectionException {
    MetadataConnection metadataConn = metadataConn();

    if (!fieldName.endsWith("__c")) {
      fieldName += "__c";
    }

    String fullName = objectName + "." + fieldName;

    CustomField customField = new CustomField();
    customField.setFullName(fullName);
    customField.setLabel(fieldLabel);
    customField.setType(fieldType);
    if (fieldLength != null) customField.setLength(fieldLength);
    if (fieldPrecision != null) customField.setPrecision(fieldPrecision);
    if (fieldScale != null) customField.setScale(fieldScale);
    if (fieldType == FieldType.LongTextArea) customField.setVisibleLines(4);
    if (fieldType == FieldType.MultiselectPicklist) customField.setVisibleLines(4);
    if (fieldType == FieldType.Checkbox)  customField.setDefaultValue("false");

    if (values != null) {
      ValueSet valueSet = new ValueSet();
      ValueSetValuesDefinition valuesDefinition = new ValueSetValuesDefinition();
      // TODO: could set sorted, but for now, assuming the caller passed in the order they want
      valuesDefinition.setValue(
          values.stream().map(v -> {
            CustomValue customValue = new CustomValue();
            customValue.setFullName(v);
            customValue.setLabel(v);
            customValue.setIsActive(true);
            return customValue;
          }).toArray(CustomValue[]::new)
      );
      valueSet.setValueSetDefinition(valuesDefinition);
      valueSet.setRestricted(false);
      customField.setValueSet(valueSet);
    } else if (globalPicklistName != null) {
      ValueSet valueSet = new ValueSet();
      valueSet.setValueSetName(globalPicklistName);
      customField.setValueSet(valueSet);
    }

    Arrays.stream(metadataConn.createMetadata(new Metadata[]{customField})).forEach(log::info);

    // TODO: Allow multiple fields to be created at once, then do a single set of profile updates.
    if (profilesMetadata == null) {
      ListMetadataQuery listMetadataQuery = new ListMetadataQuery();
      listMetadataQuery.setType("Profile");
      profilesMetadata = metadataConn.listMetadata(new ListMetadataQuery[]{listMetadataQuery}, 0.0);
    }

    List<Profile> profiles = Arrays.stream(profilesMetadata).map(p -> {
          Profile profile = new Profile();
          profile.setFullName(p.getFullName());

          ProfileFieldLevelSecurity fieldSec = new ProfileFieldLevelSecurity();
          fieldSec.setField(fullName);
          fieldSec.setEditable(true);
          fieldSec.setReadable(true);
          profile.setFieldPermissions(new ProfileFieldLevelSecurity[]{fieldSec});

          return profile;
        }).collect(Collectors.toList());

    // API limits us to a max of 10 at a time (by default, NPSP has 17).
    final List<List<Profile>> profileBatches = Lists.partition(profiles, 10);
    for (List<Profile> profileBatch : profileBatches) {
      Arrays.stream(metadataConn.updateMetadata(profileBatch.toArray(new Metadata[0]))).forEach(log::info);
    }

    // TODO: Add to page layouts?
  }

  public SaveResult[] addFields(String layoutName, String sectionLabel, List<String> fieldNames) throws ConnectionException {
    ReadResult readResult = metadataConn().readMetadata("Layout", new String[] {layoutName});
    Metadata[] metadata = readResult.getRecords();
    if (metadata.length == 0) {
      // Layout not found
      return null;
    }
    Layout layout = (Layout) metadata[0];
    LayoutSection layoutSection = getOrCreateSection(layout, sectionLabel);
    LayoutColumn layoutColumn = getOrCreateColumn(layoutSection);

    List<LayoutItem> layoutItems = new ArrayList<>(Arrays.asList(layoutColumn.getLayoutItems()));
    List<LayoutItem> newLayoutItems = fieldNames.stream()
                    .map(this::layoutItem)
                    .collect(Collectors.toList());
    layoutItems.addAll(newLayoutItems);
    layoutColumn.setLayoutItems(layoutItems.toArray(new LayoutItem[0]));
    return metadataConn().updateMetadata(metadata);
  }

  private LayoutSection getOrCreateSection(Layout layout, String sectionLabel) {
    List<LayoutSection> layoutSections = new ArrayList<>(Arrays.asList(layout.getLayoutSections()));
    Optional<LayoutSection> existingSection = layoutSections.stream()
            .filter(layoutSection -> sectionLabel.equals(layoutSection.getLabel()))
            .findFirst();
    if (existingSection.isPresent()) {
      return existingSection.get();
    } else {
      LayoutSection layoutSection = layoutSection(sectionLabel);
      layoutSections.add(layoutSection);
      layout.setLayoutSections(layoutSections.toArray(new LayoutSection[0]));
      return layoutSection;
    }
  }

  private LayoutColumn getOrCreateColumn(LayoutSection layoutSection) {
    List<LayoutColumn> layoutColumns = new ArrayList<>(Arrays.asList(layoutSection.getLayoutColumns()));
    if (CollectionUtils.isNotEmpty(layoutColumns)) {
      return layoutColumns.get(0);
    } else {
      LayoutColumn layoutColumn = new LayoutColumn();
      layoutColumns.add(layoutColumn);
      layoutSection.setLayoutColumns(layoutColumns.toArray(new LayoutColumn[0]));
      return layoutColumn;
    }
  }

  private LayoutSection layoutSection(String sectionLabel) {
    LayoutSection layoutSection = new LayoutSection();
    layoutSection.setLabel(sectionLabel);
    // TODO: define defaults
    layoutSection.setStyle(LayoutSectionStyle.OneColumn);
    layoutSection.setDetailHeading(true);
    return layoutSection;
  }

  private LayoutItem layoutItem(String fieldName) {
    LayoutItem layoutItem = new LayoutItem();
    layoutItem.setField(fieldName);
    //TODO: define defaults
    layoutItem.setBehavior(UiBehavior.Edit);
    return layoutItem;
  }

}