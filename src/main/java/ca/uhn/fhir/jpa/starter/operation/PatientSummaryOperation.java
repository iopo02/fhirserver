package ca.uhn.fhir.jpa.starter.operation;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.SortOrderEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PatientSummaryOperation
 * ========================
 * Operação FHIR custom: GET /fhir/Patient/{id}/$summary
 *
 * Parâmetros opcionais:
 *   obsLimit  (integer, default 5, máx 20)
 *   since     (date YYYY-MM-DD)
 *   status    (string) — clinicalStatus das Conditions
 *
 * Devolve Bundle FHIR R4 COLLECTION com profile IPS contendo:
 *   - DiagnosticReport com:
 *       #extractive-summary  — resumo estruturado em Português
 *       #abstractive-summary — parágrafo narrativo em PT + EN
 *   - Patient, Conditions, Observations, Medications, Allergies, Procedures
 */
@Component
public class PatientSummaryOperation implements IResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(PatientSummaryOperation.class);

    private static final int    DEFAULT_OBS_LIMIT = 5;
    private static final int    MAX_OBS_LIMIT     = 20;
    private static final String LOINC_SYSTEM      = "http://loinc.org";
    private static final String IPS_PROFILE       =
            "http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips";
    private static final String DATE_FORMAT       = "yyyy-MM-dd";
    private static final String DATETIME_FORMAT   = "yyyy-MM-dd HH:mm:ss";

    private static final Map<String, String> PATIENT_SEARCH_PARAM = Map.of(
            "Condition",           "subject",
            "Observation",         "subject",
            "MedicationRequest",   "subject",
            "MedicationStatement", "subject",
            "AllergyIntolerance",  "patient",
            "Procedure",           "subject",
            "DiagnosticReport",    "subject"
    );

    private final DaoRegistry daoRegistry;

    public PatientSummaryOperation(DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    @Override
    public Class<Patient> getResourceType() {
        return Patient.class;
    }

    // =========================================================
    // OPERAÇÃO $summary
    // =========================================================

    @Operation(name = "$summary", idempotent = true, type = Patient.class)
    public Bundle patientSummary(
            @IdParam                                     IdType      patientId,
            @OperationParam(name = "obsLimit", min = 0) IntegerType obsLimitParam,
            @OperationParam(name = "since",    min = 0) DateType    sinceParam,
            @OperationParam(name = "status",   min = 0) StringType  statusParam) {

        String id         = patientId.getIdPart();
        int    obsLimit   = resolveObsLimit(obsLimitParam);
        String since      = sinceParam  != null ? sinceParam.getValueAsString() : null;
        String condStatus = statusParam != null ? statusParam.getValue()        : null;

        log.info("[PatientSummary] id={} obsLimit={} since={} status={}",
                id, obsLimit, since, condStatus);

        Patient        patient     = loadPatient(id);
        List<Resource> conditions  = fetchConditions(id, condStatus);
        List<Resource> observations= fetchObservations(id, since, obsLimit);
        List<Resource> medications = fetchMedications(id);
        List<Resource> allergies   = fetchResources("AllergyIntolerance", id, null);
        List<Resource> procedures  = fetchResources("Procedure",          id, since);

        DiagnosticReport report = buildDiagnosticReport(
                id, patient, conditions, observations, medications, allergies, procedures);

        return buildBundle(report, patient,
                conditions, observations, medications, allergies, procedures);
    }

    // =========================================================
    // DIAGNOSTIC REPORT
    // =========================================================

    private DiagnosticReport buildDiagnosticReport(
            String patientId, Patient patient,
            List<Resource> conditions, List<Resource> observations,
            List<Resource> medications, List<Resource> allergies,
            List<Resource> procedures) {

        DiagnosticReport report = new DiagnosticReport();
        report.setId("patient-summary-" + patientId);
        report.setStatus(DiagnosticReport.DiagnosticReportStatus.FINAL);
        report.setSubject(new Reference("Patient/" + patientId));
        report.setIssued(new Date());
        report.addCategory(codeable(LOINC_SYSTEM, "60591-5", "Patient Summary Document"));
        report.setCode(codeable(LOINC_SYSTEM, "60591-5", "Patient Summary Document"));

        // ---- Extractive (PT) ----
        String extractiveText = buildExtractiveText(
                patientId, patient, conditions, observations, medications, allergies, procedures);
        Observation extractiveObs = new Observation();
        extractiveObs.setId("extractive-summary");
        extractiveObs.setStatus(Observation.ObservationStatus.FINAL);
        extractiveObs.setCode(codeable(LOINC_SYSTEM, "11369-6", "Resumo Clínico Estruturado"));
        extractiveObs.setSubject(new Reference("Patient/" + patientId));
        extractiveObs.setValue(new StringType(extractiveText));
        report.addContained(extractiveObs);

        // ---- Abstractive (PT + EN) ----
        String abstractiveText = buildAbstractiveText(
                patientId, patient, conditions, observations, medications, allergies, procedures);
        Observation abstractiveObs = new Observation();
        abstractiveObs.setId("abstractive-summary");
        abstractiveObs.setStatus(Observation.ObservationStatus.FINAL);
        abstractiveObs.setCode(codeable(LOINC_SYSTEM, "11369-6", "Resumo Narrativo / Narrative Summary"));
        abstractiveObs.setSubject(new Reference("Patient/" + patientId));
        abstractiveObs.setValue(new StringType(abstractiveText));
        report.addContained(abstractiveObs);

        report.addResult()
                .setReference("#extractive-summary")
                .setDisplay("Resumo Clínico Estruturado");
        report.addResult()
                .setReference("#abstractive-summary")
                .setDisplay("Resumo Narrativo / Narrative Summary");

        return report;
    }

    // =========================================================
    // EXTRACTIVE SUMMARY — Português
    // =========================================================

    private String buildExtractiveText(
            String patientId,
            Patient patient,
            List<Resource> conditions, List<Resource> observations,
            List<Resource> medications, List<Resource> allergies,
            List<Resource> procedures) {

        StringBuilder sb = new StringBuilder();

        // Cabeçalho com metadados de origem
        sb.append("=== Resumo Clínico do Paciente ===\n\n");
        sb.append("Fonte: Servidor HAPI FHIR R4 (HL7 FHIR R4)\n");
        sb.append("Recurso: /fhir/Patient/").append(patientId).append("/$summary\n");
        sb.append("Gerado em: ")
          .append(new SimpleDateFormat(DATETIME_FORMAT).format(new Date()))
          .append(" UTC\n");
        sb.append("─────────────────────────────────────\n\n");

        // Dados demográficos
        sb.append("Paciente: ").append(patientDisplayName(patient)).append("\n");

        if (patient != null && patient.getGender() != null) {
            String gender = switch (patient.getGender().toCode()) {
                case "male"   -> "Masculino";
                case "female" -> "Feminino";
                case "other"  -> "Outro";
                default       -> "Desconhecido";
            };
            sb.append("Género: ").append(gender).append("\n");
        }

        // Correcção do formato da data de nascimento
        if (patient != null && patient.hasBirthDate()) {
            String birthDateStr = new SimpleDateFormat(DATE_FORMAT)
                    .format(patient.getBirthDate());
            int age = calculateAge(patient.getBirthDate());
            sb.append("Data de Nascimento: ").append(birthDateStr)
              .append(" (").append(age).append(" anos)\n");
        }

        if (patient != null && patient.hasIdentifier()) {
            patient.getIdentifier().stream()
                    .filter(id -> Identifier.IdentifierUse.OFFICIAL.equals(id.getUse())
                            || id.getUse() == null)
                    .findFirst()
                    .ifPresent(id -> sb.append("ID Hospital: ")
                            .append(id.getValue()).append("\n"));
        }

        // Condições clínicas
        sb.append("\nCondições Clínicas:\n");
        if (conditions.isEmpty()) {
            sb.append("  - Nenhuma registada\n");
        } else {
            conditions.forEach(r -> {
                Condition c      = (Condition) r;
                String    text   = codeableText(c.getCode());
                String    status = c.hasClinicalStatus()
                        ? " [" + codeableText(c.getClinicalStatus()) + "]" : "";
                sb.append("  - ").append(text).append(status).append("\n");
            });
        }

        // Observações — separar laboratoriais de narrativas
        Map<Boolean, List<Resource>> partitioned = observations.stream()
                .collect(Collectors.partitioningBy(
                        r -> ((Observation) r).hasValueQuantity()));

        List<Resource> labObs       = partitioned.get(true);
        List<Resource> narrativeObs = partitioned.get(false);

        if (!labObs.isEmpty()) {
            sb.append("\nResultados Laboratoriais:\n");
            labObs.forEach(r -> {
                Observation o = (Observation) r;
                sb.append("  - ").append(codeableText(o.getCode()))
                  .append(": ").append(observationValue(o)).append("\n");
            });
        }

        if (!narrativeObs.isEmpty()) {
            sb.append("\nObservações Clínicas:\n");
            narrativeObs.forEach(r -> {
                Observation o   = (Observation) r;
                String      val = observationValue(o);
                sb.append("  - ").append(codeableText(o.getCode()));
                if (val != null) sb.append(": ").append(val);
                sb.append("\n");
            });
        }

        if (observations.isEmpty()) {
            sb.append("\nObservações:\n  - Nenhuma registada\n");
        }

        // Medicação
        sb.append("\nMedicação Atual:\n");
        if (medications.isEmpty()) {
            sb.append("  - Nenhuma registada\n");
        } else {
            medications.forEach(r ->
                    sb.append("  - ").append(medicationText(r)).append("\n"));
        }

        // Alergias
        sb.append("\nAlergias / Intolerâncias:\n");
        if (allergies.isEmpty()) {
            sb.append("  - Nenhuma registada\n");
        } else {
            allergies.forEach(r -> {
                AllergyIntolerance a = (AllergyIntolerance) r;
                sb.append("  - ").append(codeableText(a.getCode())).append("\n");
            });
        }

        // Procedimentos
        sb.append("\nProcedimentos:\n");
        if (procedures.isEmpty()) {
            sb.append("  - Nenhum registado\n");
        } else {
            procedures.forEach(r -> {
                Procedure p      = (Procedure) r;
                String    status = p.hasStatus()
                        ? " [" + p.getStatus().toCode() + "]" : "";
                sb.append("  - ").append(codeableText(p.getCode()))
                  .append(status).append("\n");
            });
        }

        return sb.toString();
    }

    // =========================================================
    // ABSTRACTIVE SUMMARY — Português + Inglês
    // =========================================================

    private String buildAbstractiveText(
            String patientId, Patient patient,
            List<Resource> conditions, List<Resource> observations,
            List<Resource> medications, List<Resource> allergies,
            List<Resource> procedures) {

        long labCount = observations.stream()
                .filter(r -> ((Observation) r).hasValueQuantity()).count();

        StringBuilder sb = new StringBuilder();

        // ── Versão Portuguesa ──────────────────────────────────
        sb.append("[PT] ");
        sb.append("Paciente ").append(patientDisplayName(patient))
          .append(" (ID: ").append(patientId).append(")");

        if (patient != null && patient.hasBirthDate()) {
            sb.append(", ").append(calculateAge(patient.getBirthDate())).append(" anos");
        }
        sb.append(". ");

        if (!conditions.isEmpty()) {
            String condList = conditions.stream()
                    .map(r -> codeableText(((Condition) r).getCode()))
                    .filter(s -> !"Unknown".equals(s))
                    .limit(3)
                    .collect(Collectors.joining(", "));
            if (!condList.isBlank())
                sb.append("Condições activas: ").append(condList).append(". ");
        } else {
            sb.append("Sem condições clínicas registadas. ");
        }

        if (!medications.isEmpty())
            sb.append("Com ").append(medications.size()).append(" medicamento(s) activo(s). ");

        if (!allergies.isEmpty()) {
            String allergyList = allergies.stream()
                    .map(r -> codeableText(((AllergyIntolerance) r).getCode()))
                    .filter(s -> !"Unknown".equals(s))
                    .limit(3)
                    .collect(Collectors.joining(", "));
            if (!allergyList.isBlank())
                sb.append("Alergias conhecidas: ").append(allergyList).append(". ");
        }

        if (labCount > 0)
            sb.append(labCount).append(" resultado(s) laboratorial(ais) recente(s). ");

        if (!procedures.isEmpty())
            sb.append(procedures.size()).append(" procedimento(s) registado(s).");

        // ── Separador ─────────────────────────────────────────
        sb.append("\n\n[EN] ");

        // ── Versão Inglesa ────────────────────────────────────
        sb.append("Patient ").append(patientDisplayName(patient))
          .append(" (ID: ").append(patientId).append(")");

        if (patient != null && patient.hasBirthDate()) {
            sb.append(", ").append(calculateAge(patient.getBirthDate())).append(" years old");
        }
        sb.append(". ");

        if (!conditions.isEmpty()) {
            String condList = conditions.stream()
                    .map(r -> codeableText(((Condition) r).getCode()))
                    .filter(s -> !"Unknown".equals(s))
                    .limit(3)
                    .collect(Collectors.joining(", "));
            if (!condList.isBlank())
                sb.append("Active conditions: ").append(condList).append(". ");
        } else {
            sb.append("No recorded clinical conditions. ");
        }

        if (!medications.isEmpty())
            sb.append("On ").append(medications.size()).append(" medication(s). ");

        if (!allergies.isEmpty()) {
            String allergyList = allergies.stream()
                    .map(r -> codeableText(((AllergyIntolerance) r).getCode()))
                    .filter(s -> !"Unknown".equals(s))
                    .limit(3)
                    .collect(Collectors.joining(", "));
            if (!allergyList.isBlank())
                sb.append("Known allergies: ").append(allergyList).append(". ");
        }

        if (labCount > 0)
            sb.append(labCount).append(" recent lab result(s). ");

        if (!procedures.isEmpty())
            sb.append(procedures.size()).append(" procedure(s) on record.");

        return sb.toString().trim();
    }

    // =========================================================
    // BUNDLE FINAL
    // =========================================================

    private Bundle buildBundle(
            DiagnosticReport report, Patient patient,
            List<Resource> conditions, List<Resource> observations,
            List<Resource> medications, List<Resource> allergies,
            List<Resource> procedures) {

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.COLLECTION);
        Meta meta = new Meta();
        meta.setLastUpdated(new Date());
        meta.addProfile(IPS_PROFILE);
        bundle.setMeta(meta);

        bundle.addEntry().setResource(report);
        if (patient != null) bundle.addEntry().setResource(patient);
        conditions  .forEach(r -> bundle.addEntry().setResource(r));
        observations.forEach(r -> bundle.addEntry().setResource(r));
        medications .forEach(r -> bundle.addEntry().setResource(r));
        allergies   .forEach(r -> bundle.addEntry().setResource(r));
        procedures  .forEach(r -> bundle.addEntry().setResource(r));

        return bundle;
    }

    // =========================================================
    // FETCH DE RECURSOS
    // =========================================================

    private Patient loadPatient(String patientId) {
        try {
            @SuppressWarnings("unchecked")
            IFhirResourceDao<IBaseResource> dao = daoRegistry.getResourceDao("Patient");
            IBaseResource resource = dao.read(new IdType("Patient", patientId));
            if (resource instanceof Patient p) return p;
        } catch (Exception e) {
            log.warn("[PatientSummary] Patient não encontrado: {}", patientId);
        }
        throw new ResourceNotFoundException("Patient/" + patientId);
    }

    private List<Resource> fetchConditions(String patientId, String status) {
        SearchParameterMap params = baseParams(patientId, "subject", null);
        if (status != null && !status.isBlank())
            params.add("clinical-status", new TokenParam(status));
        return executeSearch("Condition", params);
    }

    private List<Resource> fetchObservations(String patientId, String since, int limit) {
        SearchParameterMap params = baseParams(patientId, "subject", since);
        params.setSort(new SortSpec("date", SortOrderEnum.DESC));
        return executeSearch("Observation", params)
                .stream().limit(limit).collect(Collectors.toList());
    }

    private List<Resource> fetchMedications(String patientId) {
        List<Resource> meds = fetchResources("MedicationStatement", patientId, null);
        if (meds.isEmpty()) meds = fetchResources("MedicationRequest", patientId, null);
        return meds;
    }

    private List<Resource> fetchResources(
            String resourceType, String patientId, String since) {
        String searchParam = PATIENT_SEARCH_PARAM.get(resourceType);
        if (searchParam == null) return Collections.emptyList();
        return executeSearch(resourceType, baseParams(patientId, searchParam, since));
    }

    private SearchParameterMap baseParams(
            String patientId, String searchParam, String since) {
        SearchParameterMap params = new SearchParameterMap();
        params.setLoadSynchronous(true);
        params.add(searchParam, new ReferenceParam("Patient/" + patientId));
        if (since != null && !since.isBlank()) {
            try {
                Date sinceDate = Date.from(LocalDate.parse(since)
                        .atStartOfDay(ZoneOffset.UTC).toInstant());
                params.setLastUpdated(
                        new DateRangeParam().setLowerBoundInclusive(sinceDate));
            } catch (Exception e) {
                log.warn("[PatientSummary] Parâmetro 'since' inválido: {}", since);
            }
        }
        return params;
    }

    private List<Resource> executeSearch(
            String resourceType, SearchParameterMap params) {
        List<Resource> result = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            IFhirResourceDao<IBaseResource> dao = daoRegistry.getResourceDao(resourceType);
            if (dao == null) return result;
            IBundleProvider provider = dao.search(params);
            Integer size = provider.size();
            if (size == null || size <= 0) return result;
            provider.getResources(0, size).forEach(r -> {
                if (r instanceof Resource res) result.add(res);
            });
        } catch (Exception e) {
            log.error("[PatientSummary] Erro ao pesquisar {}: {}", resourceType, e.getMessage());
        }
        return result;
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private String codeableText(CodeableConcept concept) {
        if (concept == null) return "Unknown";
        if (concept.hasText()) return concept.getText();
        if (!concept.getCoding().isEmpty()) {
            Coding c = concept.getCodingFirstRep();
            if (c.hasDisplay()) return c.getDisplay();
            if (c.hasCode())    return c.getCode();
        }
        return "Unknown";
    }

    private String observationValue(Observation obs) {
        if (obs == null || !obs.hasValue()) return null;
        Type v = obs.getValue();
        if (v instanceof Quantity    q)  return q.getValue() + " " + q.getUnit();
        if (v instanceof StringType  st) return st.getValue();
        if (v instanceof BooleanType bt) return bt.getValue().toString();
        return v.primitiveValue();
    }

    private String medicationText(Resource r) {
        if (r instanceof MedicationStatement ms)
            return codeableText(ms.getMedicationCodeableConcept());
        if (r instanceof MedicationRequest mr)
            return codeableText(mr.getMedicationCodeableConcept());
        return "Unknown";
    }

    private String patientDisplayName(Patient patient) {
        if (patient == null || !patient.hasName()) return "Unknown";
        return patient.getNameFirstRep().getNameAsSingleString();
    }

    private int calculateAge(Date birthDate) {
        LocalDate birth = birthDate.toInstant()
                .atZone(ZoneOffset.UTC).toLocalDate();
        return Period.between(birth, LocalDate.now()).getYears();
    }

    private CodeableConcept codeable(String system, String code, String display) {
        CodeableConcept cc = new CodeableConcept();
        cc.addCoding().setSystem(system).setCode(code).setDisplay(display);
        return cc;
    }

    private int resolveObsLimit(IntegerType param) {
        if (param == null || param.getValue() == null) return DEFAULT_OBS_LIMIT;
        return Math.max(1, Math.min(param.getValue(), MAX_OBS_LIMIT));
    }
}