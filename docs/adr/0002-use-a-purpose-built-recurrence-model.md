# Use a purpose-built recurrence model instead of RRULE

Duit will persist a small typed recurrence language covering every-N day/week/month/year patterns, selected weekdays or calendar dates, bounds, pauses, and per-occurrence additions, removals, moves, and overrides. Raw RRULE is rejected because its much larger and sometimes ambiguous calendar language would leak into the product and test surface; RRULE may be added later only as an import/export adapter, not as the domain model.
