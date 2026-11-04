# M9 Data Quality Framework

Resident quality rules are State Pack configuration, evaluated after mapping and before canonical persistence. Supported rules are legacy `required`/`allowedValues`, plus `PATTERN`, `DATE_RANGE`, `REFERENCE_LIST`, `UNIQUE_WITHIN_BATCH`, and fixed `CROSS_FIELD` operators (`ALL_OR_NONE`, `REQUIRES`, `NOT_EQUALS`).

An ERROR is written to `dq_result` and `canonical_processing_error`; its canonical record is not inserted, while later records in the batch still run. A WARNING is written to `dq_result`, the canonical record is retained, and the legacy warning audit record is also retained. `GET /api/dq/batches/{batchId}` reports the latest persisted evaluation with bounded samples and returns 404 for an unknown batch. `rulesEvaluated` is the actual number of configured Resident-rule evaluations performed (`mapped input records × configured Resident rules`), not the number of findings.

Reference lists are platform YAML loaded into generic `reference_dataset`/`reference_value` tables; evaluation reads those persisted values after loading. The MVP LGD list is one such dataset; no State-specific reference table or hard-coded Java LGD list exists. `CROSS_FIELD` is intentionally limited to `ALL_OR_NONE`, `REQUIRES`, and `NOT_EQUALS`; it is not an expression language.
