# Nonprofit Nucleus

**The complete nonprofit toolbox. Automated.**

[Nonprofit Nucleus](https://www.impactupgrade.com/nonprofit-nucleus) is an automation and integration engine, containing all the tools an organization needs to connect the dots and tackle a wide variety of processes.

Nucleus focuses on four key areas:

1. Freeing up staff members to focus on the core mission, instead of being distracted by tedious and error-prone tech.
2. Increasing your mission's reach with innovative, engaging strategies.
3. Reducing risks, increasing security & privacy, and preventing fraud.
4. Enabling nonprofit-specific concepts within commercial, industry-leading platforms.

# Nonprofit Nucleus: Engine

Behind the scenes, Nucleus has a powerful and flexible engine that's able to talk to a wide variety of platforms. Unlike "plugins" in the marketplace, we take an external, API-driven approach that's highly customizable. It's 100% nonprofit specific -- everything you need and nothing you don't. And although "building block" platforms like Zapier and IFTTT have their place, Nucleus instead focuses on automating processes end-to-end, right out of the box.

## Architecture

Nucleus is broken down into a set of layers, containing a mix of abstract logic as well as vendor-specific clients. The end goal is to focus the business logic in abstract way, reusable within a variety of flows.

The layers, somewhat resembling an MVC pattern:

- **Environment:** Defines configurations and context specific to a nonprofit, such as the platform vendors and unique data.
- **Controllers:** Endpoints receiving REST API calls, SOAP API calls, or webhook events. These come from a mix of third party platforms, portals, or other integration/automation focused web apps (ex: full-service Donor Portals).
- **Services:**
    - **Logic:**
        - "Logic" services handle a distinct unit of business logic in an abstract way. This could be how to handle a new donor/donation, what to do with an incoming SMS, etc. -- all agnostic to the specific vendors.
        - Operates against an abstract data model. Ex: DonorService (create contact records if the donor doesn't already exist) and DonationService (handle the actual transaction and recurring subscription).
    - **Segment:**
        - A "segment" is essentially a vertical or category of platforms used in the nonprofit arena. These include CRMs, payment gateways, messaging services, etc.
        - Defines a common interface that must be implemented for a specific vendor to be a part of a segment and available for workflows. An example of this is `CrmService`, which defines how a specific CRM acts as the primary source-of-truth for donor and donation data.
        - Service implementations are then vendor-specific "wrappers", such as `SfdcCrmService` (Salesforce CRM).
- **Clients:** Code that directly wraps a vendor's SDK or raw API, such as `StripeClient`, `TwilioClient`, and `SfdcClient`. Think of this like a DAO layer, but backed by API integration instead of a database. All query language and vendor-specific SDKs should be isolated here.

## Tech Stack

- Java 16
- standard Java service interfaces (ServiceLoader), primarily driving SegmentServices and a nonprofit's ability to override them
- Jetty (embedded)
- Jersey (REST)
- Apache CXF (SOAP)
- Platform SDKs, provided by the vendors or [Impact Upgrade open source libraries](https://github.com/impactupgrade)
- Misc, standard libs in the JVM ecosystem

And that's it. Raw, clean, Java code, as God intended.

### But, Nucleus sounds like EIP.

Yep. In the future, it might make sense to refactor this platform to use an enterprise integration pattern framework, such as Apache Camel. But for now, we're having a blast with the simplicity.

TODO: diagrams

## Functional and Integration Tests

TODO

# Impact Upgrade

[Impact Upgrade](https://www.impactupgrade.com) is a consulting and software engineering company that solely focuses on the unique needs of nonprofits. We upgrade your impact and get you back to your mission.

### Your mission is too important!

At [Impact Upgrade](https://www.impactupgrade.com), we know your mission is too important to be held back by unorganized data or overwhelming technology.  Trying to keep up with all of the software options to effectively manage and expand a nonprofit can be exhausting.   We solely partner with nonprofits to simplify technology for outreach, operations, donations, and much more.  We serve nonprofits through fresh approaches, unifying current platforms, and custom software development.  [Impact Upgrade](https://www.impactupgrade.com) is your one stop shop for all things digital!

### Save Time

We understand you have enough on your plate to worry about, and that’s why we want to be your tech partner when challenges arise.  Let us save you hours of work each week so you can focus on what really matters.

### Gain Expertise

Before solely working with nonprofits, our team had careers with Disney, Twilio, the Department of Defense and other Fortune 500 companies.

### Discover New Perspectives

With technology constantly changing, don't try to figure it all out on your own. We literally become a part of your staff to oversee: platforms & data, processes & efficiency, tech vendors, backups & monitoring, and security.


### Our process is simple.

- **Listen** - We discuss your goals and existing pain points.
- **Evaluate** - We look for ways to simplify your processes and untangle digital messes.
- **Partner Together** - We are with you every step of the way, executing the plan and attacking new challenges as they arise.

## Copyright

Copyright 3River Development LLC, DBA Impact Upgrade. All rights reserved.
