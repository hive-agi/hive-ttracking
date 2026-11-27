# ttrack golden rows — `iso.edn`

ttrack's own conformance suite for the ISO-8601 / RFC 3339 codecs, the calendar
arithmetic, the zone seam and completed-years. **89 rows**, each
`{:id :op :in :expect}`, where `:expect` is a value or `{:refused <reason>}`.

## Why these ids, and who owns them

The ids are **English, generic and stable**, and they are grouped by **edge-case
category**, never by a funeraria row name (plan §6b, amendment 1). `ttrack` must
not know `funeraria`: the 54-row map from calendario's GENERIC rows onto these
ids lives on the CONSUMER side, in calendario's parity test (plan steps 11–12).
**Any later id change must be told to febd7c04**, whose parity test pins these
ids.

`America/Sao_Paulo` appears here as **test data** for the DST cases, not as a
funeraria default: ttrack has no default zone.

## Categories (id namespace → rows)

| namespace | rows | covers |
|---|---|---|
| `:day/…` | 26 | the ISO day codec, the 1900–2199 range edges, leap days, `days-between`, `add-days` |
| `:month/…` | 7 | the ISO month codec, impossible/instant/range refusals |
| `:time/…` | 12 | the legacy time layouts `H`/`H:M`/`H:M:S`, the `HH:MM`/`HH:MM:SS` format rule, out-of-range |
| `:period/…` | 7 | the period format, `current-month`/`previous-month`, the half-open instant interval |
| `:zone/…` | 14 | DST gap and overlap in America/Sao_Paulo (2018-11-04, 2019-02-16/17), day-start, `today` in three zones |
| `:instant/…` | 7 | the RFC 3339 instant codec, offset normalisation, instant≠day |
| `:age/…` | 16 | completed-years under BOTH leap policies, incl. the 2000-02-29 birth |

## The eight asserted categories

The coverage test (`golden-covers-every-category`) asserts at least one row in
each of: DST gap and overlap; leap days; completed-years under both policies;
the 1900–2199 range edges (1899 and 2200 refused); the day/month/period codecs;
the legacy time formats; instant vs day; `today` in UTC, America/Sao_Paulo and
Asia/Tokyo.

## Full id list

### `:day/…`
`:day/iso-accepted`, `:day/iso-padded-accepted`, `:day/format`,
`:day/format-pads`, `:day/zero-go-refused`, `:day/range-min-accepted`,
`:day/range-max-accepted`, `:day/range-1899-refused`, `:day/range-2200-refused`,
`:day/leap-2024-02-29-accepted`, `:day/leap-2000-02-29-accepted`,
`:day/non-leap-2023-02-29-refused`, `:day/non-leap-1900-02-29-refused`,
`:day/impossible-2026-02-31`, `:day/instant-not-a-day-refused`,
`:day/text-not-a-day-refused`, `:day/non-padded-refused`,
`:day/not-text-refused`, `:day/days-between-piso`, `:day/days-between-negative`,
`:day/days-between-across-dst`, `:day/days-between-across-dst-end`,
`:day/add-days`, `:day/add-days-across-dst`, `:day/add-days-year-rollover`,
`:day/add-days-out-of-range-refused`.

### `:month/…`
`:month/iso-accepted`, `:month/iso-padded-accepted`, `:month/format`,
`:month/instant-refused`, `:month/day-string-refused`, `:month/month-13-refused`,
`:month/range-1899-refused`.

### `:time/…`
`:time/legacy-h`, `:time/legacy-hm`, `:time/legacy-hms`, `:time/legacy-zero`,
`:time/format-hm`, `:time/format-hms`, `:time/format-midnight`,
`:time/trailing-colon-refused`, `:time/text-refused`, `:time/hour-24-50-refused`,
`:time/hour-24-00-refused`, `:time/minute-60-refused`.

### `:period/…`
`:period/format`, `:period/current-month`, `:period/previous-month`,
`:period/end-exclusive`, `:period/contains-start-inclusive`,
`:period/contains-end-exclusive`, `:period/contains-last-second`.

### `:zone/…`
`:zone/sao-paulo-2018-11-04-gap-refused`,
`:zone/sao-paulo-2018-11-04-gap-0030-refused`,
`:zone/sao-paulo-2018-11-04-gap-edge-0100`,
`:zone/sao-paulo-2018-11-03-before-gap`, `:zone/sao-paulo-2018-11-04-day-start`,
`:zone/sao-paulo-2019-02-17-overlap-earliest`,
`:zone/sao-paulo-2019-02-16-day-start`, `:zone/sao-paulo-2019-02-17-day-start`,
`:zone/period-across-dst-day`, `:zone/unknown-zone-refused`, `:zone/today-utc`,
`:zone/today-sao-paulo`, `:zone/today-tokyo`,
`:zone/today-sao-paulo-before-midnight`.

### `:instant/…`
`:instant/parse-rfc3339`, `:instant/parse-offset-normalised`,
`:instant/format`, `:instant/format-millis`, `:instant/parse-day-string-refused`,
`:instant/instant-to-day-sao-paulo`,
`:instant/instant-to-day-sao-paulo-previous`.

### `:age/…`
`:age/leap-birth-read-2024-02-28-feb-28`, `:age/leap-birth-read-2024-02-28-mar-01`,
`:age/leap-birth-read-2024-02-29-feb-28`, `:age/leap-birth-read-2024-02-29-mar-01`,
`:age/leap-birth-read-2025-02-28-feb-28`, `:age/leap-birth-read-2025-02-28-mar-01`,
`:age/leap-birth-read-2025-03-01-feb-28`, `:age/leap-birth-read-2025-03-01-mar-01`,
`:age/ordinary-anniversary`, `:age/ordinary-day-before-anniversary`,
`:age/anniversary-01-01`, `:age/anniversary-01-03`,
`:age/leap-birth-before-march-anniversary`, `:age/before-birth-refused`,
`:age/missing-policy-refused`, `:age/unknown-policy-refused`.
