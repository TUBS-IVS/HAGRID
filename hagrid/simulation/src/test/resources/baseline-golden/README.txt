Lausitz LMD fixture references
=============================
carriers-golden.sha256: jsprit Core/Analysis 2.0.0, IO 1.8, Java 21.
carriers-golden-jsprit-1.8.sha256: original jsprit 1.8 reference retained.

Migration checked 2026-10-07 with the identical stageLmdFixture and 5 iterations
in separate version-specific JVMs. Old/new XML differs only in DHL's service
order and corresponding intermediate departure times. All three DHL services
are on the depot link; both orders have zero travel distance, 22 minutes total
service time, one tour and EUR 170 cost. Hermes remains byte-identical, serves
its one job in one tour and costs EUR 171.60 (EUR 170 + 4000 m * 0.0004).
Demand, vehicle definitions, unassigned-job metadata, tour start/end and scores
are unchanged. Independent semantic assertions guard against a cheaper hash
being accepted because jobs were dropped. Cache-hit output remains byte-identical.

Old SHA256: 6ce4bf6e4dc3eeff3ed1f486e8be678b079c825721a74499cf89c05052f49628
New SHA256: f78899810ff6df479ebf330ac0e22d6e66b335679b6d47395f2dd8e3b935990c
