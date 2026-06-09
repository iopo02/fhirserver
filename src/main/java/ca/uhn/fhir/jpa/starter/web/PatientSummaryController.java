package ca.uhn.fhir.jpa.starter.web;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceParam;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("control")
public class PatientSummaryController {

    private final DaoRegistry daoRegistry;
    private final FhirContext fhirContext;

    private static final Map<String, String> RESOURCE_PATIENT_SEARCH_PARAM = Map.of(
            "Condition", "subject",
            "Observation", "subject",
            "MedicationRequest", "subject",
            "MedicationStatement", "subject",
            "AllergyIntolerance", "patient",
            "Procedure", "subject",
            "DiagnosticReport", "subject");

    public PatientSummaryController(
            DaoRegistry daoRegistry,
            FhirContext fhirContext) {
        this.daoRegistry = daoRegistry;
        this.fhirContext = fhirContext;
    }

    @GetMapping(value = "patient-summary/{patientId}", produces = "application/fhir+json")
    public ResponseEntity<String> getPatientSummary(
            @PathVariable("patientId") String patientId,
            HttpServletRequest request) {

        Patient patient = (Patient) readResource("Patient", patientId);

        if (patient == null) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Patient not found: " + patientId);
        }

        /*
         * =========================================================
         * CREATE DIAGNOSTIC REPORT
         * =========================================================
         */

        DiagnosticReport diagnosticReport = new DiagnosticReport();

        diagnosticReport.setId("patient-summary-" + patientId);

        diagnosticReport.setStatus(
                DiagnosticReport.DiagnosticReportStatus.FINAL);

        diagnosticReport.setSubject(
                new Reference("Patient/" + patientId));

        diagnosticReport.setIssued(new Date());

        CodeableConcept category = new CodeableConcept();
        Coding categoryCoding = category.addCoding();
        categoryCoding.setSystem("http://terminology.hl7.org/CodeSystem/v2-0074");
        categoryCoding.setCode("GEN");
        categoryCoding.setDisplay("General");
        diagnosticReport.addCategory(category);

        CodeableConcept reportCode = new CodeableConcept();
        reportCode.setText("Clinical Patient Summary");
        diagnosticReport.setCode(reportCode);

        /*
         * =========================================================
         * BUILD EXTRACTIVE SUMMARY (Portuguese)
         * =========================================================
         */

        StringBuilder extractiveSummary = new StringBuilder();

        extractiveSummary.append("Resumo Clínico do Paciente\n\n");

        extractiveSummary.append("Paciente: ");

        if (!patient.getName().isEmpty()) {

            HumanName name = patient.getNameFirstRep();

            extractiveSummary.append(
                    name.getNameAsSingleString());
        }

        extractiveSummary.append("\n");

        if (patient.getGender() != null) {

            extractiveSummary.append("Gênero: ")
                    .append(patient.getGender().toCode())
                    .append("\n");
        }

        if (patient.getBirthDate() != null) {

            extractiveSummary.append("Data de Nascimento: ")
                    .append(patient.getBirthDate())
                    .append("\n");
        }

        /*
         * =========================================================
         * CONDITIONS
         * =========================================================
         */

        List<Resource> conditions = fetchResources("Condition", patientId);

        extractiveSummary.append("\nCondições:\n");

        if (conditions.isEmpty()) {

            extractiveSummary.append("- Nenhuma registrada\n");

        } else {

            for (Resource resource : conditions) {

                Condition c = (Condition) resource;

                String text = extractCodeableConceptText(
                        c.getCode());

                extractiveSummary.append("- ")
                        .append(text)
                        .append("\n");
            }
        }

        /*
         * =========================================================
         * OBSERVATIONS
         * =========================================================
         */

        List<Resource> observations = fetchResources("Observation", patientId);

        extractiveSummary.append("\nObservações Recentes:\n");

        if (observations.isEmpty()) {

            extractiveSummary.append("- Nenhuma registrada\n");

        } else {

            int limit = Math.min(observations.size(), 5);

            for (int i = 0; i < limit; i++) {

                Observation obs = (Observation) observations.get(i);

                String obsCode = extractCodeableConceptText(obs.getCode());
                String value = extractObservationValue(obs);

                extractiveSummary.append("- ")
                        .append(obsCode);

                if (value != null) {

                    extractiveSummary.append(": ")
                            .append(value);
                }       

                extractiveSummary.append("- ")
                        .append(obsCode);

                if (value != null) {

                    extractiveSummary.append(": ")
                            .append(value);
                }

                extractiveSummary.append("\n");
            }
        }

        /*
         * =========================================================
         * MEDICATIONS
         * =========================================================
         */

        List<Resource> medications = fetchResources(
                "MedicationStatement",
                patientId);

        // Fallback to MedicationRequest if no MedicationStatement found
        if (medications.isEmpty()) {
            medications = fetchResources(
                    "MedicationRequest",
                    patientId);
        }

        extractiveSummary.append("\nMedicamentos:\n");

        if (medications.isEmpty()) {

            extractiveSummary.append("- Nenhum registrado\n");

        } else {

            for (Resource resource : medications) {

                String medText = "Unknown";

                if (resource instanceof MedicationStatement) {
                    MedicationStatement med = (MedicationStatement) resource;
                    medText = extractCodeableConceptText(
                            med.getMedicationCodeableConcept());
                } else if (resource instanceof MedicationRequest) {
                    MedicationRequest med = (MedicationRequest) resource;
                    medText = extractCodeableConceptText(
                            med.getMedicationCodeableConcept());
                }

                extractiveSummary.append("- ")
                        .append(medText)
                        .append("\n");
            }
        }

        /*
         * =========================================================
         * ALLERGIES
         * =========================================================
         */

        List<Resource> allergies = fetchResources(
                "AllergyIntolerance",
                patientId);

        extractiveSummary.append("\nAlergias:\n");

        if (allergies.isEmpty()) {

            extractiveSummary.append("- Nenhuma registrada\n");

        } else {

            for (Resource resource : allergies) {

                AllergyIntolerance allergy = (AllergyIntolerance) resource;

                String allergyText = extractCodeableConceptText(
                        allergy.getCode());

                extractiveSummary.append("- ")
                        .append(allergyText)
                        .append("\n");
            }
        }

        /*
         * =========================================================
         * CREATE EXTRACTIVE SUMMARY OBSERVATION
         * =========================================================
         */

        Observation extractiveObs = new Observation();
        extractiveObs.setId("extractive-summary");
        extractiveObs.setStatus(Observation.ObservationStatus.FINAL);

        CodeableConcept extractiveCode = new CodeableConcept();
        extractiveCode.setText("Extractive summary");
        extractiveObs.setCode(extractiveCode);

        extractiveObs.setValue(
                new StringType(extractiveSummary.toString()));

        diagnosticReport.addContained(extractiveObs);

        /*
         * =========================================================
         * CREATE ABSTRACTIVE SUMMARY OBSERVATION
         * =========================================================
         */

        StringBuilder abstractiveSummary = new StringBuilder();
        abstractiveSummary.append("Clinical summary for patient ")
                .append(patientId)
                .append(": ");

        if (!patient.getName().isEmpty()) {
            abstractiveSummary.append(patient.getNameFirstRep().getNameAsSingleString());
        }

        abstractiveSummary.append(". ");

        if (!conditions.isEmpty()) {
            abstractiveSummary.append("Has ")
                    .append(conditions.size())
                    .append(" recorded condition(s). ");
        }

        if (!observations.isEmpty()) {
            abstractiveSummary.append("Recent observations available. ");
        }

        if (!medications.isEmpty()) {
            abstractiveSummary.append("Currently on ")
                    .append(medications.size())
                    .append(" medication(s). ");
        }

        if (!allergies.isEmpty()) {
            abstractiveSummary.append("Has ")
                    .append(allergies.size())
                    .append(" recorded allergy/allergies.");
        }

        Observation abstractiveObs = new Observation();
        abstractiveObs.setId("abstractive-summary");
        abstractiveObs.setStatus(Observation.ObservationStatus.FINAL);

        CodeableConcept abstractiveCode = new CodeableConcept();
        abstractiveCode.setText("Abstractive summary");
        abstractiveObs.setCode(abstractiveCode);

        abstractiveObs.setValue(
                new StringType(abstractiveSummary.toString()));

        diagnosticReport.addContained(abstractiveObs);

        /*
         * =========================================================
         * ADD RESULT REFERENCES
         * =========================================================
         */

        diagnosticReport.addResult()
                .setReference("#extractive-summary")
                .setDisplay("Extractive summary");

        diagnosticReport.addResult()
                .setReference("#abstractive-summary")
                .setDisplay("Abstractive summary");

        /*
         * =========================================================
         * BUILD FINAL BUNDLE
         * =========================================================
         */

        Bundle bundle = new Bundle();

        bundle.setType(Bundle.BundleType.COLLECTION);

        bundle.addEntry()
                .setResource(diagnosticReport);

        bundle.addEntry()
                .setResource(patient);

        conditions.forEach(r -> bundle.addEntry().setResource(r));

        observations.forEach(r -> bundle.addEntry().setResource(r));

        medications.forEach(r -> bundle.addEntry().setResource(r));

        allergies.forEach(r -> bundle.addEntry().setResource(r));

        /*
         * =========================================================
         * SERIALIZE
         * =========================================================
         */

        String json = fhirContext
                .newJsonParser()
                .setPrettyPrint(true)
                .encodeResourceToString(bundle);

        return ResponseEntity
                .ok()
                .contentType(
                        MediaType.parseMediaType(
                                "application/fhir+json"))
                .body(json);
    }

    private IBaseResource readResource(
            String resourceType,
            String resourceId) {

        try {

            @SuppressWarnings("unchecked")
            IFhirResourceDao<IBaseResource> dao = daoRegistry.getResourceDao(resourceType);

            if (dao == null) {
                return null;
            }

            return dao.read(
                    new IdType(resourceType, resourceId));

        } catch (Exception e) {

            return null;
        }
    }

    private IBundleProvider searchResources(
            String resourceType,
            String patientId) {

        String searchParam = RESOURCE_PATIENT_SEARCH_PARAM.get(resourceType);

        if (searchParam == null) {
            return null;
        }

        @SuppressWarnings("unchecked")
        IFhirResourceDao<IBaseResource> dao = daoRegistry.getResourceDao(resourceType);

        if (dao == null) {
            return null;
        }

        SearchParameterMap params = new SearchParameterMap();

        params.setLoadSynchronous(true);

        ReferenceParam patientRef = new ReferenceParam();
        patientRef.setValue("Patient/" + patientId);
        params.add(searchParam, patientRef);

        ReferenceParam bareRef = new ReferenceParam();
        bareRef.setValue(patientId);
        params.add(searchParam, bareRef);

        // Add patient alias for resources that support it
        if ("Observation".equals(resourceType) ||
                "MedicationStatement".equals(resourceType) ||
                "Condition".equals(resourceType) ||
                "Procedure".equals(resourceType) ||
                "DiagnosticReport".equals(resourceType)) {

            ReferenceParam patientAlias = new ReferenceParam();
            patientAlias.setValue("Patient/" + patientId);
            params.add("patient", patientAlias);

            ReferenceParam barePatientAlias = new ReferenceParam();
            barePatientAlias.setValue(patientId);
            params.add("patient", barePatientAlias);
        }

        return dao.search(params);
    }

    private List<Resource> fetchResources(
            String resourceType,
            String patientId) {

        List<Resource> result = new ArrayList<>();

        try {

            IBundleProvider provider = searchResources(resourceType, patientId);

            if (provider == null) {
                return result;
            }

            Integer size = provider.size();

            if (size == null || size <= 0) {
                return result;
            }

            List<IBaseResource> resources = provider.getResources(0, size);

            for (IBaseResource resource : resources) {

                if (resource instanceof Resource r) {

                    result.add(r);
                }
            }

        } catch (Exception e) {

            e.printStackTrace();
        }

        return result;
    }

    private String extractCodeableConceptText(
            CodeableConcept concept) {

        if (concept == null) {
            return "Unknown";
        }

        if (concept.hasText()) {
            return concept.getText();
        }

        if (!concept.getCoding().isEmpty()) {

            Coding coding = concept.getCodingFirstRep();

            if (coding.hasDisplay()) {
                return coding.getDisplay();
            }

            if (coding.hasCode()) {
                return coding.getCode();
            }
        }

        return "Unknown";
    }

    private String extractObservationValue(
            Observation observation) {

        if (observation == null ||
                !observation.hasValue()) {

            return null;
        }

        Type value = observation.getValue();

        if (value instanceof Quantity quantity) {

            return quantity.getValue()
                    + " "
                    + quantity.getUnit();
        }

        return value.toString();
    }
}