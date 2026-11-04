# Demo addendum to Parts 12 and 13: Tier 2 parsers and Tier 3 plug-ins on real scheme files

Parts 12 and 13 of the developer guide prove that the three Tier 2 parsers compile and that the three Tier 3 jars
load once approved. This addendum shows **why they matter**: two legacy scheme workbooks that UDDP cannot onboard
cleanly until the parsers tidy them, onboarded end to end through the Onboarding Studio, with the resulting
canonical and Golden values.

Everything here is synthetic demo data. The Ladakh-specific logic (Bhoti dictionary, Rabjung calendar, the
`LA-<district>-<category>-<serial>` card format) lives only in the demo jars outside this repository; the platform
code stays state-neutral.

| File (in `D:\projects\uddp-demo\excel`) | Tier | Parsers it needs | Story |
| --- | --- | --- | --- |
| `G_pension_ledger_legacy.xlsx` | 2 | `date-text@1.0`, `mobile-number@1.0`, `amount-text@1.0` | A pension ledger typed by block-office clerks: one date column in six formats, phone cells with two numbers and country codes, amounts as `Rs. 1,000/-`, `₹1000`, `Rs 2 lakh`. |
| `H_traditional_register_bhoti.xlsx` | 3 | `bhoti-name@1.0`, `rabjung-year@1.0`, `ration-card@1.0` (and `mobile-number@1.0`) | An older household register kept in Bhoti script, birth years in the Rabjung calendar, ration cards written loosely (`la/leh/phh/101`). |
| `H2_traditional_register_next.xlsx` | 3 | as H | The next delivery of the register: one new person and one name the plug-in cannot read. |

Create the files once (they are not part of `demo.zip`):

```powershell
python D:\projects\uddp-demo\make_plugin_demo_files.py D:\projects\uddp-demo\excel
```

Run Parts 3 to 6 first (Social Welfare, Agriculture, Animal Husbandry, Food ration), because the people in G and H
are the same people, written differently.

---

## 1. Where the plug-in test form is

Sign in as anyone with shaping view rights (platform.admin, data.engineer, state.admin, data.steward or auditor).

* **Configure → Shaping plug-ins** (`/settings/shaping-plugins`). The first panel, **Test a parser or plug-in**, is
  the test form. Pick a parser, type a value, press **Test** (or Enter). The result lists every output key, the
  confidence and any warnings. Every built-in parser row and every LOADED plug-in has its own **Test** button that
  picks it in the form.
* **Configure → Word lists** (`/reference-data/word-lists`) has the same form at the bottom of the page, for checking
  a word list change.

`Options (JSON, optional)` passes the parser's own step keys, for example `{"dayFirst": false}` for `date-text@1.0`.
The form runs the parser exactly as a source's `parse:` step would; nothing is saved.

What the guide's "Word lists → Test" meant is the second location; the first one is new and is the one to use in
Parts 12 and 13.

## 2. Part 12 addendum: Tier 2 on a legacy pension ledger

### 2.1 Why this matters

The block offices kept an old-age and widow pension ledger before the department system existed. Six of its seven
pensioners are already in UDDP from the Social Welfare file (Part 3); one is not. The ledger is the only place the
arrears paid are recorded, and the department wants to know who is still being paid from both lists.

Without the Tier 2 parsers the ledger cannot be onboarded honestly:

| Column | As typed | What goes wrong without a parser |
| --- | --- | --- |
| `DATE_OF_BIRTH` | `12 Mar 1958`, `30.06.1955`, `14-Aug-1950`, `January 2, 1968`, `7/7/45`, `03/1951` | No single date format fits, so the Studio cannot offer a conversion; every row is UNREADABLE_DATE. Dropping the column loses the strongest matching evidence. |
| `CONTACT_NUMBERS` | `+91 94190-00001 / 09419000002`, `(94190) 00005`, `9419000007 or 9419000017`, `94190.00019` | The 10-digit mobile rule fails, and matching compares `+91 94190-00001 / 09419000002` with `9419000001`: no match. |
| `MONTHLY_AMOUNT`, `ARREARS_PAID` | `Rs. 1,000/-`, `₹1000`, `INR 1,000`, `Rs 0.6 lakh`, `Rs 2 lakh` | The entitlement amount is not a number, so the benefit cannot be stored or compared. |

With the parsers each messy cell becomes one clean value, the original cell is kept, and the mapping, matching and
benefit reports work on the clean values.

### 2.2 Prove the parsers first (after the restart in Part 12)

Configure → Shaping plug-ins → Test a parser or plug-in:

| Parser | Value | Expected result |
| --- | --- | --- |
| `mobile-number@1.0` | `+91 94190-00001 / 09419000002` | mobile `9419000001`, otherMobiles `9419000002`, confidence 1 |
| `date-text@1.0` | `21 Nov 1979` | date `1979-11-21`, precision DAY, confidence 1 |
| `date-text@1.0` | `12/3/58` | date `1958-03-12`, precision DAY, confidence 0.9, warning "day and month could be swapped" |
| `date-text@1.0` | `12/3/58` with options `{"dayFirst": false}` | date `1958-12-03` |
| `date-text@1.0` | `03/1958` | date `1958-03`, precision MONTH, confidence 0.7 |
| `date-text@1.0` | `1958` | date `1958`, precision YEAR, confidence 0.6, warning "only the year is known" |
| `amount-text@1.0` | `Rs. 1.5 lakh` | amount `150000`, currency INR |
| `amount-text@1.0` | `₹75,500/-` | amount `75500` |
| `amount-text@1.0` | `2 crore` | amount `20000000` |
| `amount-text@1.0` | `Nil` | no values, confidence 0, warning "not an amount" |

### 2.3 Onboard the ledger (data.engineer)

1. **Onboarding Studio → New source.** Pack ID `la-pension-ledger`, code `LAPL`, display name
   `Pension ledger (block offices, legacy)`. Create draft.
2. **Upload and profile:** `G_pension_ledger_legacy.xlsx`. Point at the profile: `DATE_OF_BIRTH` has no date format
   and `CONTACT_NUMBERS` is plain text. Save and continue.
3. **Shape.** Tick the proposals: header detection (the header is on row 3) and `name-relation@1.0` on
   `NAME_PARENTAGE`. Then, in the new panel **Add a field parser**, add five parsers (Column, Parser, Add parser;
   leave "Name the new columns" empty to use the column name):

   | Column | Parser | New columns in the After table |
   | --- | --- | --- |
   | `DATE_OF_BIRTH` | `date-text@1.0` | `date_of_birth_date`, `date_of_birth_precision` |
   | `CONTACT_NUMBERS` | `mobile-number@1.0` | `contact_numbers_mobile`, `contact_numbers_other_mobiles` |
   | `MONTHLY_AMOUNT` | `amount-text@1.0` | `monthly_amount_amount`, `monthly_amount_currency` |
   | `ARREARS_PAID` | `amount-text@1.0` | `arrears_paid_amount`, `arrears_paid_currency` |
   | `SANCTIONED_ON` | `date-text@1.0` | `sanctioned_on_date`, `sanctioned_on_precision` |

   The After table now shows, next to each original column, the clean value (see 2.4). The warnings list shows
   `amount-text: not an amount` (BL-004 arrears are "Nil") and `date-text: day and month could be swapped`
   (BL-006 sanctioned "3/12/25"). Warnings never contain the value. The generated YAML shows the five `parse:` steps.
   Save and continue.
4. **Map.** Map the clean columns, not the typed ones:

   | Column | Target |
   | --- | --- |
   | `person_name` | Full name |
   | `relation_name` (and the other name-relation outputs, as in Part 3) | Parentage or spouse name |
   | `GENDER` | Gender |
   | `date_of_birth_date` | Date of birth (no conversion is shown: one value is a month, `1951-03`; the platform reads the parser's ISO dates of any precision) |
   | `contact_numbers_mobile` | Mobile number |
   | `DISTRICT_LGD` | District (LGD code) |
   | `VILLAGE_LGD` | Village (LGD code) |
   | `SCHEME` | Scheme |
   | `LEDGER_NO` | Record ID and Benefit / enrolment ID |
   | `monthly_amount_amount` | Entitlement amount |
   | `sanctioned_on_date` | Benefit start date (`date:YYYY-MM-DD`) |

   Remove any suggestion on the typed columns (`DATE_OF_BIRTH`, `CONTACT_NUMBERS`, `MONTHLY_AMOUNT`, `SANCTIONED_ON`)
   and on `contact_numbers_other_mobiles`. Unmapped columns, including the typed originals, the second mobile and
   `arrears_paid_amount`, are kept on the record as extensions, so nothing is lost.
5. **Crosswalks.** Gender (M, F) and scheme (OAP → OLD-AGE-PENSION, WP → WIDOW-PENSION) are suggested from the
   sources published earlier.
6. **Rules and Preview.** Keep the defaults. Expected: 7 rows, 0 blocking errors.

   *Optional "before" shot:* in Shape, remove the five parsers, map `DATE_OF_BIRTH`, `CONTACT_NUMBERS` and
   `MONTHLY_AMOUNT` directly, and Preview: dates are UNREADABLE_DATE, the entitlement amount is not a number and the
   mobile rule warns on most rows. Add the parsers back before you continue.
7. **Submit, approve (state.admin), Onboard, approve release (platform.admin)**, the same sequence as Part 3.
   Mapping progress: 10 of 10 file rows read (title, subtitle, header and 7 pensioners), New or changed 7, Skipped 3
   (the three rows above the data), Rejected 0.

### 2.4 Expected results

Every value below was produced by the actual parser code on the actual workbook cells.

| Ledger | Date of birth (typed → canonical) | Mobile (typed → canonical, other) | Monthly / arrears | Golden resident |
| --- | --- | --- | --- | --- |
| BL-001 | `12 Mar 1958` → 1958-03-12 | `+91 94190-00001 / 09419000002` → 9419000001, other 9419000002 | 1000 / 120000 | Tsering Angchuk (existing) |
| BL-002 | `30.06.1955` → 1955-06-30 | `094190 00004` → 9419000004 | 1000 / 12000 | Rigzin Lhamo (existing) |
| BL-003 | `14-Aug-1950` → 1950-08-14 | `(94190) 00005` → 9419000005 | 1000 / 60000 | Mohd Ali (existing) |
| BL-004 | `January 2, 1968` → 1968-01-02 | `9419000007 or 9419000017` → 9419000007, other 9419000017 | 1000 / — (`Nil`, warning) | Sonam Wangchuk (existing) |
| BL-005 | `19-04-1958` → 1958-04-19 | `94190 00008` → 9419000008 | 1000 / 24000 | Tsewang Dolkar (existing) |
| BL-006 | `7/7/45` → 1945-07-07 | `+919419000012` → 9419000012 | 1000 / 200000 | Jigmet Namgyal (existing) |
| BL-007 | `03/1951` → 1951-03-01, precision MONTH, birth year 1951 | `94190.00019` → 9419000019 | 1000 / — (blank) | Dawa Dolma (new) |

Sanctioned dates: 2019-04-01, 2018-06-15, 2017-03-01, 2025-11-21, 2024-01-05, 2025-12-03 (confidence 0.9),
2019-04-01.

What to show:

* **Matching.** Six ledger rows match people already in UDDP because name, date of birth and mobile now agree with
  the Social Welfare file (Match evidence shows the per-field "why matched"). Expect +7 canonical records and +1 Golden
  resident (Dawa Dolma). A pair that lands in the review queue instead of AUTO_MATCH is a threshold matter, not a
  parser failure; approve it as data.steward.
* **Golden Resident 360** for Tsering Angchuk: one more source (`LAPL`), mobile 9419000001 agreeing with the other
  departments; the ledger's typed value and the second number stay visible as source extensions.
* **Duplicate benefits** (Reports, as program.user): the six matched pensioners now hold an old-age or widow pension
  from two sources (Social Welfare and the ledger). This is the finding the department wanted, and it only exists
  because the amounts, dates and phones were normalised first.

## 3. Part 13 addendum: Tier 3 on a Bhoti household register

### 3.1 Why this matters

The older household survey was written in Bhoti script. Its birth years are Rabjung cycle years, and its ration-card
numbers are the regional format written by hand. The same six people are already in UDDP in Latin script (pension,
agriculture, census, ration). Without the plug-ins none of the three columns can be compared with anything else:

| Column | Example | Plug-in output | Used for |
| --- | --- | --- | --- |
| `NAME_BHOTI` | ཚེ་རིང་ ཨང་ཕྱུག | script BHOTI, latinGuess `Tsering Angchuk` | the comparable name; the Bhoti original is kept |
| `BIRTH_YEAR_RABJUNG` | `Rabjung 16 year 32` | year 1958, Earth Dog, cycle 16, year-in-cycle 32 | date of birth with YEAR precision, birth year 1958 |
| `RATION_CARD` | `la/leh/phh/101` | normalised `LA-LEH-PHH-000101`, category PHH (Priority household), district Leh Ladakh, LGD 9, serial 000101 | household ID identical to the Food ration file, district LGD code |

The region-specific knowledge (a Bhoti dictionary, the Rabjung epoch, the district codes inside the card) is in
three approved jars, not in the platform.

### 3.2 Prove the plug-ins first (after Part 13's second restart, all three LOADED)

Configure → Shaping plug-ins. Click **Test** under each plug-in's "Provides" (it picks the parser in the form):

| Parser | Value | Expected result |
| --- | --- | --- |
| `bhoti-name@1.0` | ཚེ་རིང་ ཨང་ཕྱུག | script BHOTI, bhoti (the input), latinGuess Tsering Angchuk, confidence 1 |
| `bhoti-name@1.0` | ཚེ་རིང་ ཨང་ཕྱུག Tsering Angchuk | script MIXED, latin Tsering Angchuk, latinGuess Tsering Angchuk |
| `bhoti-name@1.0` | ཀུན་མཛེས་ ཨང་མོ | script BHOTI, no latinGuess, confidence 0.6, warning "a Bhoti word is not in the demo dictionary" |
| `ration-card@1.0` | `LA-LEH-PHH-000101` | category PHH, categoryLabel Priority household, district Leh Ladakh, districtLgd 9, serial 000101, normalised LA-LEH-PHH-000101 |
| `ration-card@1.0` | `la/kgl/aay/45` | district Kargil, districtLgd 6, category AAY, serial 000045, normalised LA-KGL-AAY-000045 |
| `ration-card@1.0` | `LA-DEL-PHH-1` | no values, confidence 0, warning "not a LA-<district>-<category>-<serial> ration card" |
| `rabjung-year@1.0` | `Rabjung 16 year 32` | year 1958, cycle 16, yearInCycle 32, elementAnimal Earth Dog |
| `rabjung-year@1.0` | `Earth Sheep 16` | year 1979, yearInCycle 53 |
| `rabjung-year@1.0` | `17-38` | year 2024, Wood Dragon |

(Copy the Bhoti values from `H_traditional_register_bhoti.xlsx` or `E_plugin_and_wordlist_test_values.xlsx`.)

### 3.3 Onboard the register (data.engineer)

1. **New source:** Pack ID `la-traditional-register`, code `LATR`, display name
   `Traditional household register (Bhoti)`.
2. **Upload and profile:** `H_traditional_register_bhoti.xlsx`. The profile keeps the Bhoti script as is.
3. **Shape.** Tick header detection (header on row 2). In **Add a field parser** add:

   | Column | Parser | New columns |
   | --- | --- | --- |
   | `NAME_BHOTI` | `bhoti-name@1.0` | `name_bhoti_script`, `name_bhoti_bhoti`, `name_bhoti_latin_guess` |
   | `BIRTH_YEAR_RABJUNG` | `rabjung-year@1.0` | `birth_year_rabjung_year`, `_cycle`, `_year_in_cycle`, `_element_animal` |
   | `RATION_CARD` | `ration-card@1.0` | `ration_card_district`, `_district_lgd`, `_category`, `_category_label`, `_serial`, `_normalised` |
   | `MOBILE` | `mobile-number@1.0` (Tier 2) | `mobile_mobile` |

   The Parser list offers the jars' parsers only because they are LOADED; a NOT_APPROVED jar's parser cannot be
   picked or run. Save and continue.
4. **Map:**

   | Column | Target |
   | --- | --- |
   | `name_bhoti_latin_guess` | Full name |
   | `GENDER` | Gender |
   | `birth_year_rabjung_year` | Date of birth (read as 1958-01-01 with precision YEAR) |
   | `ration_card_district_lgd` | District (LGD code) |
   | `ration_card_normalised` | Family · Household / family ID |
   | `VILLAGE_LGD` | Village (LGD code) |
   | `mobile_mobile` | Mobile number |
   | `SURVEY_NO` | Record ID |

   Remove the suggestions on `NAME_BHOTI`, `BIRTH_YEAR_RABJUNG`, `RATION_CARD` and `MOBILE` (the typed originals stay
   on the record as extensions, so the Bhoti name is preserved), and remove the *Social category* suggestion on
   `ration_card_category`: PHH is a ration-card category, not a caste or community category.

   Why Full name takes the Latin guess: this register has no Latin-script name at all, and the comparable name is what
   matching needs. The original Bhoti value stays on the record, and Golden survivorship still prefers the officially
   recorded names of the other departments.
5. **Crosswalks, Rules, Preview.** Expected Preview: 6 rows, 0 blocking errors. Submit, approve, onboard, release.
   Mapping progress for this first delivery: 8 of 8 file rows read (the title row, the header row and 6 people),
   New or changed 6, Skipped 2 (the title and header rows), Rejected 0.

### 3.4 Expected results

| Survey | Name (Bhoti → Latin) | Birth year (Rabjung → Gregorian) | Card (typed → normalised) | District | Golden resident |
| --- | --- | --- | --- | --- | --- |
| TR-01 | ཚེ་རིང་ ཨང་ཕྱུག → Tsering Angchuk | Rabjung 16 year 32 → 1958 (Earth Dog) | la/leh/phh/101 → LA-LEH-PHH-000101 | 9 Leh Ladakh | Tsering Angchuk (existing) |
| TR-02 | པདྨ་ སྒྲོལ་མ → Padma Dolma | 16-36 → 1962 (Water Tiger) | LA LEH PHH 000101 → LA-LEH-PHH-000101 | 9 | Padma Dolma (existing) |
| TR-03 | བསྟན་འཛིན་ ནོར་བུ → Stanzin Norbu | Earth Sheep 16 → 1979 | la-leh-nphh-102 → LA-LEH-NPHH-000102 | 9 | Stanzin Norbu (existing) |
| TR-04 | རིག་འཛིན་ ལྷ་མོ → Rigzin Lhamo | Rabjung 16 year 29 → 1955 (Wood Sheep) | LA-LEH-AAY-103 → LA-LEH-AAY-000103 | 9 | Rigzin Lhamo (existing) |
| TR-05 | བསོད་ནམས་ དབང་ཕྱུག → Sonam Wangchuk | 16-42 → 1968 (Earth Monkey) | la/kgl/phh/202 → LA-KGL-PHH-000202 | 6 Kargil | Sonam Wangchuk (existing) |
| TR-06 | ཚེ་རིང་ སྒྲོལ་མ → Tsering Dolma | Rabjung 16 year 34 → 1960 (Iron Mouse) | LA-LEH-PHH-105 → LA-LEH-PHH-000105 | 9 | Tsering Dolma of Basgo, born 1960 (existing) |

What to show:

* **Matching.** Each register row carries a Latin name, a 10-digit mobile, a district, a village and a birth year that
  agree with the person's other records, so it joins that person's Golden resident (Match evidence explains the
  pair). The date of birth is year-precision (1958-01-01, precision YEAR), so it is not compared as an exact day; the
  birth year 1958 is what agrees. A pair offered in the review queue rather than auto-matched is approved by a steward.
* **Two people called Tsering Dolma.** TR-06 joins the Basgo Tsering Dolma (born 1960, mobile 9419000009), not the
  Zanskar one (born 1987): the converted Rabjung year and the village agree with only one of them.
* **Golden Resident 360** for Sonam Wangchuk: a fifth source (`LATR`); the record keeps the Bhoti name, the Rabjung
  text and the card as typed, next to the Latin name, 1968 and LA-KGL-PHH-000202.
* **Households.** Tsering Angchuk and Padma Dolma form register household LA-LEH-PHH-000101, the same ID as their
  Food ration household, only because the plug-in normalised `la/leh/phh/101` and `LA LEH PHH 000101`. Golden
  households are never merged across sources automatically; Resident 360 shows the two as related families (they
  share members).

### 3.5 Next delivery: what the plug-in will not guess

In step 9, **Upload new delivery** → `H2_traditional_register_next.xlsx` → Start onboarding.

Expected run screen (the file has a title row, a header row and 8 people, so 10 file rows):

| Where | Expected | Meaning |
| --- | --- | --- |
| Mapping progress | 10 of 10 file rows read · 1 row rejected | every row of the sheet was read |
| Compared with the previous delivery | 2 new · 0 changed · 6 unchanged · 0 removed | per survey number: TR-07 and TR-08 are new, TR-01 to TR-06 are as before. TR-07 counts as new because it has a survey number, even though mapping then rejects it |
| Chunk table | File rows 10 · New or changed 1 · Skipped 8 · Rejected 1 | 1 stored (TR-08); 8 skipped = 6 unchanged people + the title and header rows; 1 rejected (TR-07) |
| MAPPED | 10 file rows · 1 new or changed records | |
| DQ_CHECKED | 7 records checked · 0 with a finding | the 6 unchanged and TR-08; TR-07 never reached the checks |
| MATCHED | 1 records matched · 1 in match groups | incremental: only the new record is matched |
| Download rejected rows (CSV) | 1 row: TR-07, name is required | with its Excel row |

The run may then pause before GOLDEN_BUILT for a release decision (a delivery with a rejected row); approve it as
platform.admin as in Part 6.

* TR-01 to TR-06: unchanged.
* TR-08 བཀྲ་ཤིས་ ནོར་བུ → Tashi Norbu, Rabjung 16 year 50 → 1976 (Fire Dragon), la/leh/phh/110 → LA-LEH-PHH-000110:
  a new resident and a new household.
* TR-07 ཀུན་མཛེས་ ཨང་མོ: the demo dictionary does not know these words, so the plug-in returns no Latin guess
  (confidence 0.6, warning) instead of inventing one. The row has no name and is rejected with its reason and Excel
  row in the rejection report. The fix is a new plug-in version with the words added, which is a new jar, a new
  SHA-256, and a new approval by two people: exactly the governance shown in Part 13. If the run pauses for a release
  decision, approve it as platform.admin as in Part 6.

## 4. The message for the audience

* **Tier 1** (configuration) handles the shape of a file. **Tier 2** adds a capability every state needs (phones,
  dates, amounts written by people) to the shared library once. **Tier 3** adds knowledge only one region has (a
  script, a calendar, a card format) as a separately approved jar.
* The same Studio screen applies all three: *Add a field parser* lists built-in parsers and loaded plug-ins side by
  side, and the generated State Pack only names them (`parser: rabjung-year@1.0`). There is no Ladakh code in the
  platform and no platform change in the jars.
* The original value is never overwritten: the typed cell, the parser's output and its confidence are all kept, and a
  value the parser cannot read is reported, not guessed.

## 5. Platform changes this addendum relies on

* Configure → Shaping plug-ins has the **Test a parser or plug-in** form, with a Test button per parser.
* Onboarding Studio → Shape has **Add a field parser** (any built-in parser or LOADED plug-in, on any column; the
  outputs are learnt by testing the parser on the first sample value of that column).
* A Resident *Date of birth* mapped without a `date:` conversion reads the parser's ISO date: `yyyy-MM-dd`,
  `yyyy-MM` (precision MONTH) or `yyyy` (precision YEAR, birth year kept). Anything else is UNREADABLE_DATE instead of
  an internal error.

Restart the backend and reload the UI after copying these changes (the Part 12 restart covers it).
