# wasm/ — kotoba-wasm deployment of the recovery-calculation-mismatch recompute

`recovery_mismatch.kotoba` is a port of `reinsurance.registry/compute-
recovery` (the treaty's own quota-share/excess-of-loss formula) plus
`reinsurance.governor`'s private `close?` tolerance check -- the
independent recompute `reinsurance.governor`'s `:recovery-calculation-
mismatch` HARD check runs against an upstream `:recovery/pay` request's
own `:claimed-recovery-amount` (see `src/reinsurance/governor.cljc`'s ns
docstring, check 5) -- into the minimal `.kotoba` language subset,
compiled to a real WASM module via `kotoba wasm emit`, and hosted via
`kototama.tender` (`test/wasm/recovery_mismatch_test.clj`).

This follows the same `kotoba wasm emit` → `kototama.tender` pipeline
`cloud-itonami-isic-6492`'s `wasm/affordability.kotoba`,
`cloud-itonami-isic-6511`'s `wasm/underwriting_decision.kotoba` and
`cloud-itonami-isic-6630`'s `wasm/fee_accrual.kotoba` established (ADR-
2607062330 addendum 5) -- `recovery_mismatch.kotoba` is closest in shape
to `fee_accrual.kotoba`: a formula recompute over integer inputs cross-
checked against a claimed figure with a tolerance band, no host imports.

## Why the source differs from `reinsurance.registry/compute-recovery`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by reading
`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj` -- no
`case`/`cond`/`pos?`/`neg?`/`and`/`or`/`when`, same finding every prior
port in this fleet documents). The port therefore:

- Uses plain positional args instead of `{:keys [...]}` map destructuring
  (no maps in the wasm-compilable subset), and turns `compute-recovery`'s
  `:treaty-type` `case` dispatch (`:quota-share` vs `:excess-of-loss`)
  into a single `treaty-type-flag` (`0`/`1`) `if` branch (no `case`/`cond`
  in the subset).
- Drops the three `throw`/`ex-info` precondition guards
  (`quota-share-pct` in `[0,1]`, `retention`/`layer-limit` `>= 0`,
  `underlying-loss-amount` `>= 0`) entirely -- a WASM export can't throw
  a JVM exception, same posture `fee_accrual.kotoba` documents:
  precondition validation stays the real `compute-recovery`'s job, the
  guest only ever sees facts a governor already validated.
- Represents `quota-share-pct` (a `[0,1]` fraction, e.g. `0.5`) as
  integer **basis points** (`quota-share-pct-bps`, e.g. `5000` = 50.00%)
  instead of a double -- avoids floating point entirely, the same
  `rate-bps` convention `fee_accrual.kotoba` uses. All money amounts
  (`underlying-loss-amount`, `coverage-limit`, `retention`,
  `layer-limit`, `claimed-recovery-amount`) are integer **cents**
  (smallest currency unit), the convention every prior wasm port in this
  fleet uses.

## Why the tolerance is KEPT (not collapsed to exact `=`)

`fee_accrual.kotoba` (cloud-itonami-isic-6630) made the opposite call for
its own formula and documented why: `fundmgmt.governor/close?`'s `1e-6`
tolerance is pure IEEE-754 double-rounding noise absorption, with no
integer analog worth porting, because that formula's fixed-point
recompute is exact-integer by construction (no division by a percentage
is ever performed).

`reinsurance.governor`'s `close?` is different, both in *size* and in
*what produces the gap it absorbs*:

```clojure
(defn- close? [a b]
  (< (Math/abs (- (double a) (double b))) 0.01))
```

- **Size**: `0.01` (one cent, absolute) is roughly four orders of
  magnitude looser than `fundmgmt.governor`'s `1e-6`. Real IEEE-754
  rounding noise on money-scale doubles (multiplying loss amounts by a
  `[0,1]` percentage) is on the order of `1e-9`..`1e-12` -- a `0.01`
  band is ~1e7-1e10x looser than needed for float-noise absorption
  alone, which is a strong signal this tolerance is sized to something
  real: the smallest actual currency denomination, the cent.
- **Source of the gap**: `compute-recovery`'s quota-share formula
  (`underlying-loss-amount * quota-share-pct`) genuinely produces
  fractional-cent amounts whenever the percentage doesn't divide the
  loss evenly (e.g. a 33.33% share of an odd number of cents -- an
  everyday occurrence, not an edge case, since real-world quota shares
  are rarely round fractions of every possible loss amount). A REAL
  claimed-recovery figure a ceding insurer files, by contrast, is
  necessarily rounded to a whole cent -- money has no sub-cent
  denomination to actually pay out. `close?` exists to accept that
  honest, load-bearing rounding gap between an exact mathematical
  recompute and a necessarily-rounded real payment -- not to discard as
  noise. (The excess-of-loss formula has no percentage division and so
  never produces a fractional-cent recompute in the first place --  on
  that branch the tolerance naturally requires exact-cents agreement,
  same as the quota-share branch would if the percentage happened to
  divide evenly. No special-casing was needed to get this right; it
  falls out of the same milli-cent-precision design below.)

Given that, collapsing straight to whole-cents integer arithmetic (like
`fee_accrual.kotoba` did) would be dishonest here: two whole-cent
integers can never differ by a nonzero amount less than 1 cent, so an
exact-cents comparison would *silently degenerate the tolerance to
exact-match* -- exactly the "invented allowance" trap the fee-accrual
README warned against, just in the opposite direction (accidentally
deleting a real tolerance instead of accidentally inventing one).

**The fix: carry the recompute in milli-cents (cents × 1000) through the
division step**, instead of truncating to whole cents immediately, so a
genuine fractional-cent remainder from the quota-share division survives
into the comparison:

```
raw-millicents = quot(underlying-loss-cents * quota-share-pct-bps, 10)
               = underlying-loss-cents * (quota-share-pct-bps / 10000) * 1000
```

`claimed-recovery-cents` (always whole cents, per above) is scaled up to
milli-cents (`× 1000`, exact, no truncation) for a same-unit comparison.
The tolerance check is then:

```
abs-diff-millicents < 1000   ; i.e. "agree to within less than one full cent"
```

the exact reduction of the original `< 0.01` dollar-scale band into this
milli-cent fixed-point domain -- a real, non-degenerate tolerance band,
not slop invented from nothing and not silently deleted either.

**Known scope limit (i32 range):** same caveat `fee_accrual.kotoba`
documents. `underlying-loss-cents * quota-share-pct-bps`,
`coverage-limit-cents * 1000`, `claimed-recovery-cents * 1000` and
`layer-recovery-cents * 1000` are each single 32-bit `i32.mul` folds
that must stay under the signed i32 ceiling (~2.147e9) or silently wrap
instead of trapping. This module's test fixtures keep all cents amounts
in the hundreds-to-low-thousands-of-dollars range, well under that bound
with headroom -- a PoC-scale limitation, not a design claim that the
formula holds at real treaty-sized (many-million-dollar) amounts.
Raising it would mean promoting to `i64` arithmetic (`i64*`/`i64-` exist
in the subset) or restructuring the fold order, a follow-up, not
attempted in this pass.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` --
the compiler only ever exports a 0-arity `main`, see `compile-wasm-expr`
in `kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are passed
through the guest's exported linear memory instead -- the same convention
every prior port in this fleet uses. A host writes seven little-endian
i32 values before calling `main()`:

| offset | field                     | unit                                            |
|--------|---------------------------|--------------------------------------------------|
| 0      | `treaty-type-flag`         | `0` = quota-share, `1` = excess-of-loss          |
| 4      | `underlying-loss-cents`    | smallest currency unit (cents)                   |
| 8      | `quota-share-pct-bps`      | basis points (`quota-share-pct * 10000`); ignored when flag = 1 |
| 12     | `coverage-limit-cents`     | cents; ignored when flag = 1                     |
| 16     | `retention-cents`          | cents; ignored when flag = 0                     |
| 20     | `layer-limit-cents`        | cents; ignored when flag = 0                     |
| 24     | `claimed-recovery-cents`   | cents -- the upstream recovery's own claim       |

`main()` returns `1` (the recomputed recovery matches the claim within
the < 1 cent tolerance band) or `0` (mismatch -- `reinsurance.governor`'s
`:recovery-calculation-mismatch` HARD violation). All seven offsets are
well below `heap-base` (2048), so they never collide with anything the
compiler itself places in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6520/wasm/recovery_mismatch.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6520/wasm/recovery_mismatch.wasm --json
```

Fleet deployment: not attempted in this pass -- see
`cloud-itonami-isic-6492`/`cloud-itonami-isic-6511` for the established
pattern.
