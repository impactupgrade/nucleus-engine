# Contributing to Nonprofit Nucleus

TODO: Overview

## Tips and Best Practices

- TODO
- Much like caveats when using an ORM, never retrieve an object, modify it, then throw it to an `update` method directly. Instead, create new, lightweight, single-purpose objects that set only the fields needed and update nothing else. Ex: instead of `Opportunity opp = sfdcClient.getOpportunity(...); opp.setName("FooBar"); sfdcClient.update(opp);`, do `Opportunity opp = new Opportunity(); opp.setId(...); opp.setName("fooBar"); sfdcClient.update(opp);`