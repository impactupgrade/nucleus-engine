Copyright 3River Development LLC, DBA Impact Upgrade. All rights reserved.

# Nucleus Common

TODO: overview

## Architecture

Nucleus is broken down into a set of layers, containing a mix of abstract concepts as well as vendor-specific logic. The end goal is to keep as much business logic in the abstract layer as possible, in a way that's reusable with other flows. As an example, when a donation comes in, records are created in the organization's CRMs. Whenever possible, keep that business logic generic and abstract so that it's directly reusable with other CRMs, rather than being specific to SFDC, etc.

The layers, somewhat resembling an MVC pattern:

- Controllers: Endpoints receiving REST API calls, SOAP API calls, or webhook events. These come from a mix of third party platforms, internal Staff Portals, or other integration/automation focused web apps (ex: LJI's Donor Portal).
- Services (multiple flavors):
    - Handles a distinct unit of business logic in an abstract way. This could be how to handle a new donor/donation, what to do with an incoming SMS, etc. -- all agnostic to the specific vendors. Operates against an abstract data model. Ex: DonorService (create contact records if they donor doesn't already exist) and DonationService (handle the actual transaction and recurring subscription).
    - Defines an interface that must be implemented for a specific vendor to be a part of a given workflow. The best examples of this are `CrmSourceService` (positions a specific CRM as the primary source-of-truth for donor/donation data retrieval) and `CrmDestinationService` (positions multiple CRMs as receipients of data to be inserted). At times, both interfaces may be implemented by a single service, such as `SfdcCrmService`, since Salesforce is often positioned for both responsibilities. But at times, some vendors may only be suited for one or the other.
- Clients: Code that directly wraps a vendor's API, such as `StripeClient`, `TwilioClient`, and `SfdcClient`. Think of this like a DAO layer, but backed by API integration instead of a database. All query language and vendor-specific SDKs should be isolated here.

TODO: diagrams

## Functional and Integration Tests

TODO

## Best Practices

- TODO
- Much like using an ORM, never retrieve an object, modify it, then throw it to an `update` method directly. Instead, create new, lightweight, single-purpose objects that set only the fields needed and update nothing else. Ex: instead of `Opportunity opp = sfdcClient.getOpportunity(...); opp.setName("FooBar"); sfdcClient.update(opp);`, do `Opportunity opp = new Opportunity(); opp.setId(...); opp.setName("fooBar"); sfdcClient.update(opp);`
