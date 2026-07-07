(ns reinsurance.facts
  "Per-jurisdiction reinsurance regulatory catalog -- the G2-style
  spec-basis table the Reinsurance Governor checks every jurisdiction/
  assess proposal against ('did the advisor cite an OFFICIAL public
  source for this jurisdiction's reinsurance-collateral/bordereaux
  requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling insurance-adjacent actor's `facts` namespace uses: a
  jurisdiction not in this table has NO spec-basis, full stop -- the
  advisor must not fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official reinsurance/
  insurance regulator (see `:provenance`); they are a STARTING catalog,
  not a from-scratch survey of all ~194 jurisdictions. Extending
  coverage is additive: add one map to `catalog`, cite a real source,
  done -- never invent a jurisdiction's requirements to make coverage
  look bigger.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  treaty-bordereaux evidence set submitted in some form; `:legal-basis`
  / `:owner-authority` / `:provenance` are the G2 citation the governor
  requires before any :jurisdiction/assess proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "金融庁 (Financial Services Agency)"
          :legal-basis "保険業法 (Insurance Business Act) -- 再保険に関する規定"
          :national-spec "日本損害保険協会 再保険契約実務指針"
          :provenance "https://www.fsa.go.jp/"
          :required-evidence ["再保険契約書案 (draft treaty wording)"
                              "分再保険料明細書 (ceded-premium bordereaux)"
                              "出再者格付証明 (ceding-insurer rating certificate)"
                              "担保要件確認書 (collateral-requirement confirmation)"]}
   "USA-NY" {:name "United States -- New York (exemplar; federalism note below)"
             :owner-authority "New York State Department of Financial Services (NYDFS)"
             :legal-basis "New York Insurance Law Article 17 (Reinsurance)"
             :national-spec "NYDFS Regulation 20/141 (credit for reinsurance)"
             :provenance "https://www.dfs.ny.gov/"
             :notes "No federal insurance regulator -- reinsurance credit/collateral requirements are regulated per-state; New York is an exemplar, not a national authority."
             :required-evidence ["Draft treaty wording"
                                 "Ceded-premium bordereaux"
                                 "Ceding-insurer rating certificate"
                                 "Collateral/trust-fund confirmation"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Prudential Regulation Authority (PRA) / Financial Conduct Authority (FCA)"
          :legal-basis "Financial Services and Markets Act 2000 -- PRA Reinsurance Directive implementation"
          :national-spec "Lloyd's/LMA standard reinsurance wordings"
          :provenance "https://www.bankofengland.co.uk/prudential-regulation"
          :required-evidence ["Draft treaty wording"
                              "Ceded-premium bordereaux"
                              "Ceding-insurer rating certificate"
                              "Collateral/trust-fund confirmation"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Versicherungsaufsichtsgesetz (VAG) §§ 209 ff. (Rückversicherung)"
          :national-spec "BaFin Rundschreiben zur Rückversicherung"
          :provenance "https://www.bafin.de/"
          :required-evidence ["Vertragsentwurf (draft treaty wording)"
                              "Prämienabrechnung (ceded-premium bordereaux)"
                              "Rating-Bescheinigung des Zedenten (ceding-insurer rating certificate)"
                              "Sicherheitenbestätigung (collateral confirmation)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to bind a treaty
  on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6520 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `reinsurance.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
