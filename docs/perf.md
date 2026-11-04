# Large-run performance

`scripts/perf/run-large.ps1 [-Rows 200000] [-InputFile M_Ration_Members_Large.csv]` runs generic Hop ingest, canonical
processing, Splink matching and the Golden build on a throwaway PostgreSQL 16 and prints time and peak memory per stage
(peak = Java + Python + Hop + database container working sets, sampled every 3 s). Target: 200k rows end to end in
under 30 minutes on a 16 GB laptop.

## Measured (2026-10-29, Windows laptop, synthetic data, seed 42)

3,000 rows across the three sample states (2,986 canonical, 1,899 golden). Blocking is OFF at this size (threshold 5,000),
so matching compared all ~4.4M pairs.

| Stage | Time | Peak memory |
|---|---|---|
| build + migrate | 6.6 s | - |
| ingest (Hop) | 12.2 s | 587 MB |
| canonical processing | 68.7 s | 603 MB |
| matching (Splink, all pairs) | 485.7 s | 9,733 MB |
| golden build | 114.2 s | 5,506 MB |
| total | 687.6 s (11.5 min) | 9,733 MB |

## Status against the target: NOT MET, and not yet measured at 200k

The 200k run has not been executed. Extrapolating the 3k run: canonical processing is about 23 ms/row (about 77 min for
200k), and the Golden build is about 38 ms/row (about 2 h). Matching with blocking (active above 5,000 records) has not
been measured at scale. The Golden build and canonical processing need optimising before the 30-minute target is realistic.
Run the script with `-Rows 200000` and replace this section with the actual numbers.

## CSV or Excel workbook

The Studio accepts a source file as `.csv` or `.xlsx`. A CSV is streamed row by row (profiling samples it without
holding it). A workbook is shaped one sheet at a time: header-detect, ditto, carry-down and section rows need neighbouring rows,
so canonical processing reads the sheet in hand from Raw (in SQL, only that sheet's rows) and keeps at most two shaped
sheets in memory. The memory bound is therefore about twice the largest sheet, not the whole workbook; a workbook of
many small sheets is cheap, one huge sheet is not. Studio profiling still opens the uploaded workbook whole and
profiles up to the first 5,000 shaped rows. For large deliveries export the sheets as CSV; the Upload step warns when an `.xlsx` is over
20 MB. Macro-enabled (`.xlsm`) and password-protected workbooks are refused at upload.