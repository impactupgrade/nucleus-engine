# Impact Nucleus

[Impact Nucleus](https://impactnucleus.com/) is an automation and integration engine, containing tools that connect the dots and tackle a wide variety of processes for both nonprofits and mission-driven businesses.

Nucleus focuses on four key areas:

1. Freeing up staff members to focus on the core mission, instead of being distracted by tedious and error-prone tech.
2. Increasing your mission's reach with innovative, engaging strategies.
3. Reducing risks, increasing security & privacy, and preventing fraud.
4. Enabling mission-specific concepts within commercial, industry-leading platforms.

# Impact Nucleus: Engine

Behind the scenes, Nucleus has a powerful and flexible engine that's able to talk to a wide variety of platforms. Unlike "plugins" in the marketplace, we take an external, API-driven approach that's highly customizable. Everything you need and nothing you don't. And although "building block" platforms like Zapier and IFTTT have their place, Nucleus instead focuses on automating processes end-to-end, with all the business logic and frequent-blindspots dealt with right out of the box.

Nucleus is a full engine on its own, as well as a development framework. It was built as an expandable library from the beginning. Organizations can customize logic and mappings from an in-depth configuration file. They can also extend and override nearly every aspect of the controllers, logic services, segment services, and clients (more on this below).

## Architecture

Nucleus is broken down into a set of layers, containing a mix of abstract logic as well as vendor-specific clients. The end goal is to focus the business logic in abstract way, reusable within a variety of flows. The layers:

- **Environment:** Defines configurations and context specific to an organization, such as the platform vendors and unique data.
- **Controllers:** Endpoints receiving REST API calls, SOAP API calls, or webhook events. These typically come from a mix of third party platforms or web apps.
- **Services:**
    - **Logic:**
        - "Logic" services handle a distinct unit of business logic in an abstract way. This could be how to handle a new customer/payment, what to do with an incoming SMS, etc. -- all agnostic to the specific vendors.
        - Operates against an abstract data model. Ex: ContactService (create contact records if the donor doesn't already exist) and DonationService (handle the actual transaction and recurring subscription).
    - **Segment:**
        - A "segment" is essentially a vertical or category of platforms. These include CRMs, payment gateways, messaging services, etc.
        - Defines a common interface that must be implemented for a specific vendor to be a part of a segment and available for workflows. An example of this is `CrmService`, which defines how a specific CRM acts as the primary source-of-truth.
        - Service implementations are then vendor-specific "wrappers", such as `SfdcCrmService` (Salesforce CRM).
- **Clients:** Code that directly wraps a vendor's SDK or raw API, such as `StripeClient`, `TwilioClient`, and `SfdcClient`. Think of this like a DAO layer, but backed by API integration instead of a database. All query language and vendor-specific SDKs should be isolated here.

## Capabilities and Workflows

TODO

## Tech Stack

- Java 16
- standard Java service interfaces (ServiceLoader), primarily driving SegmentServices and an organization's ability to override them
- Jetty (embedded)
- Jersey (REST)
- Apache CXF (SOAP)
- Platform SDKs, provided by the vendors or our additional [open source libraries](https://github.com/impactupgrade)
- Misc, standard libs in the JVM ecosystem

And that's it. Raw, clean, Java code, as God intended.

### But, Nucleus sounds like EIP.

Yep. In the future, it might make sense to refactor this platform to use an enterprise integration pattern framework, such as Apache Camel. But for now, we're having a blast with the simplicity.

TODO: diagrams

## Functional and Integration Tests

TODO

## Impact Upgrade

[Impact Upgrade](https://www.impactupgrade.com) is a software engineering and data company that partners with nonprofits and people-serving businesses. We provide tech-for-humans, making the complex simple so organizations can focus on impact. FOCUS AREAS: integration, automation, databases & CRMs, data-intensive software development, at-scale systems, long-term partnerships, and consulting. We'd love to serve you!

## License

Licensed under the PolyForm Noncommercial License 1.0.0. In its simplest form, The license grants nonprofits and individuals the right to run, modify, and distribute Nonprofit Nucleus, restricted to noncommercial purposes. See LICENSE.md for details.

## Copyright

Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
