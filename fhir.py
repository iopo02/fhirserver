from __future__ import annotations

import base64
import os
import re
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, List, Optional, Tuple

from pipeline import config
from pipeline.cleaning import clean_rtf as _clean_rtf

_ID_RE = re.compile(r"[^A-Za-z0-9\-.]")
_UID_RE = re.compile(r"^(?:\d+\.)+\d+$")


def flatten(nested: Any, parent_key: str = "", sep: str = ".") -> Dict[str, Any]:
    items: List[Tuple[str, Any]] = []
    if isinstance(nested, dict):
        for k, v in nested.items():
            new_key = f"{parent_key}{sep}{k}" if parent_key else k
            if isinstance(v, (dict, list)):
                items.extend(flatten(v, new_key, sep=sep).items())
            else:
                items.append((new_key, v))
    elif isinstance(nested, list):
        for i, v in enumerate(nested):
            new_key = f"{parent_key}{sep}{i}" if parent_key else str(i)
            if isinstance(v, (dict, list)):
                items.extend(flatten(v, new_key, sep=sep).items())
            else:
                items.append((new_key, v))
    return dict(items)


def extract_sections(flat: Dict[str, Any], prefixes: Iterable[str]) -> Dict[str, Dict[str, Any]]:
    wanted = {p.lower(): p for p in prefixes}
    sections: Dict[str, Dict[str, Any]] = {p: {} for p in prefixes}
    for key, val in flat.items():
        if val is None or val == "":
            continue
        if "." not in key:
            continue
        head, rest = key.split(".", 1)
        head_key = head.lower()
        if head_key in wanted:
            sections[wanted[head_key]][rest] = val
    return sections


def to_iso(val: Any, date_only: bool = False) -> Any:
    if not isinstance(val, str) or not val.strip():
        return val
    fmts = (
        "%d/%m/%Y %H:%M:%S",
        "%d/%m/%Y %H:%M",
        "%d/%m/%Y",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d",
    )
    for fmt in fmts:
        try:
            dt = datetime.strptime(val, fmt)
            if date_only:
                return dt.date().isoformat()
            return dt.replace(tzinfo=timezone.utc).isoformat().replace("+00:00", "Z")
        except ValueError:
            continue
    return val


def sanitize_id(value: str, fallback: str) -> str:
    cleaned = _ID_RE.sub("", value.replace(" ", "-")).lower()
    return (cleaned or fallback)[:64]


def make_resource_id(raw: Any, prefix: str, force_prefix: bool = False) -> Optional[str]:
    if not _has_required(raw):
        return None
    raw_str = str(raw).strip()
    cleaned_body = _ID_RE.sub("", raw_str.replace(" ", "-")).lower()
    full_id = f"{prefix}-{cleaned_body}"
    if len(full_id) > 64:
        digest = uuid.uuid5(uuid.NAMESPACE_URL, f"{prefix}:{raw_str}")
        return f"{prefix}-{digest.hex}"[:64]
    return full_id


def clean_rtf(text: str) -> str:
    mode = config.SUMMARY_CLEAN_MODE if os.getenv("FHIR_USE_SUMMARY_CLEAN") == "1" else config.FHIR_CLEAN_MODE
    return _clean_rtf(text, mode=mode)


def _make_uid(source: str) -> str:
    if _UID_RE.match(source):
        return source
    u = uuid.uuid5(uuid.NAMESPACE_URL, source)
    return f"2.25.{int(u)}"


def _has_required(value: Any) -> bool:
    return value is not None and value != ""


def _normalize_gender(value: str) -> Optional[str]:
    v = value.strip().lower()
    v = re.sub(r"[\s\.]+", "", v)
    if not v:
        return None
    if v in ("m", "male", "masculino", "hombre", "h", "masc", "varon", "1"):
        return "male"
    if v in ("f", "female", "femenino", "mujer", "fem", "hembra", "2"):
        return "female"
    if v in ("o", "other"):
        return "other"
    if v in ("u", "unk", "unknown", "desconocido", "n/a", "na", "0"):
        return "unknown"
    return None


def build_patient(data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    pid = data.get("ID")
    if not _has_required(pid):
        return None
    pid_raw = str(pid)
    pid_clean = make_resource_id(pid_raw, "patient")
    patient: Dict[str, Any] = {"resourceType": "Patient", "id": pid_clean}
    patient["identifier"] = [{
        "use": "official",
        "system": "urn:oid:2.16.840.1.113883.4.1",
        "value": pid_raw,
    }]
    bd = data.get("Birthdate")
    if _has_required(bd):
        patient["birthDate"] = to_iso(str(bd), date_only=True)
    gender = data.get("Gender")
    if _has_required(gender):
        norm_gender = _normalize_gender(str(gender))
        if norm_gender:
            patient["gender"] = norm_gender
    family = data.get("Name.family")
    given = [v for k, v in data.items() if k.lower().startswith("name.given")]
    if _has_required(family) or given:
        name: Dict[str, Any] = {}
        if _has_required(family):
            name["family"] = family
        if given:
            name["given"] = given
        patient["name"] = [name]
    telecoms: Dict[int, Dict[str, Any]] = {}
    for k, v in data.items():
        if k.lower().startswith("telecom["):
            idx = int(k.split("[")[1].split("]")[0])
            sub = k.split("].", 1)[1]
            telecoms.setdefault(idx, {})[sub] = v
    if telecoms:
        patient["telecom"] = [telecoms[i] for i in sorted(telecoms)]
    return patient


def build_encounter(enc_data: Dict[str, Any], rep_data: Dict[str, Any], pid: str, flat: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not _has_required(pid):
        return None
    eid = enc_data.get("AccessionNumber") or rep_data.get("AccessionNumber") or pid
    status = "finished" if rep_data.get("Validation_Timestamp") else "in-progress"
    encounter: Dict[str, Any] = {
        "resourceType": "Encounter",
        "status": status,
        "class": {
            "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
            "code": "AMB",
        },
        "subject": {"reference": f"Patient/{pid}"},
    }
    if _has_required(eid):
        encounter["id"] = make_resource_id(str(eid), "encounter")
    start = rep_data.get("Exam_Date") or flat.get("Extraction_Timestamp")
    if _has_required(start):
        encounter["period"] = {"start": to_iso(str(start))}
    return encounter


def build_diagnostic_report(rep_data: Dict[str, Any], pid: str, flat: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not _has_required(pid):
        return None
    status = "final" if rep_data.get("Validation_Timestamp") else "preliminary"
    exam = rep_data.get("Exam_Type")
    report: Dict[str, Any] = {
        "resourceType": "DiagnosticReport",
        "status": status,
        "code": {"text": str(exam) if _has_required(exam) else "Unspecified diagnostic report"},
        "subject": {"reference": f"Patient/{pid}"},
    }
    rid = rep_data.get("AccessionNumber")
    if _has_required(rid):
        report["id"] = make_resource_id(str(rid), "diagnosticreport")
    ts = rep_data.get("Validation_Timestamp") or flat.get("Extraction_Timestamp")
    if _has_required(ts):
        report["issued"] = to_iso(str(ts))
    concl = rep_data.get("Observation_clean") or rep_data.get("Report_clean")
    if _has_required(concl):
        report["conclusion"] = concl
    return report


def build_imaging_study(rep_data: Dict[str, Any], pid: str, flat: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not _has_required(pid):
        return None
    uid_source = rep_data.get("AccessionNumber") or pid
    if not _has_required(uid_source):
        return None
    uid_value = _make_uid(str(uid_source))
    study: Dict[str, Any] = {
        "resourceType": "ImagingStudy",
        "status": "available" if rep_data.get("Validation_Timestamp") else "registered",
        "subject": {"reference": f"Patient/{pid}"},
    }
    if _has_required(uid_source):
        study["id"] = make_resource_id(str(uid_source), "imagingstudy")
        study["identifier"] = [{"system": "urn:dicom:uid", "value": uid_value}]
    started = flat.get("Extraction_Timestamp") or rep_data.get("Exam_Date")
    if _has_required(started):
        study["started"] = to_iso(str(started))
    return study


def build_procedure(rep_data: Dict[str, Any], pid: str, flat: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not _has_required(pid):
        return None
    code = rep_data.get("Exam_Type")
    if not _has_required(code):
        return None
    eid = rep_data.get("AccessionNumber") or pid
    procedure: Dict[str, Any] = {
        "resourceType": "Procedure",
        "status": "completed" if rep_data.get("Validation_Timestamp") else "in-progress",
        "code": {"text": str(code)},
        "subject": {"reference": f"Patient/{pid}"},
    }
    if _has_required(eid):
        procedure["id"] = make_resource_id(eid, "proc", force_prefix=True)
    dt = rep_data.get("Validation_Timestamp") or flat.get("Extraction_Timestamp")
    if _has_required(dt):
        procedure["performedDateTime"] = to_iso(str(dt))
    return procedure


def build_observation(rep_data: Dict[str, Any], pid: str) -> Optional[Dict[str, Any]]:
    if not _has_required(pid):
        return None
    text = rep_data.get("Observation_clean") or rep_data.get("Report_clean")
    if not _has_required(text):
        return None
    oid = rep_data.get("AccessionNumber") or pid
    observation: Dict[str, Any] = {
        "resourceType": "Observation",
        "status": "final",
        "code": {"text": "Radiology narrative"},
        "subject": {"reference": f"Patient/{pid}"},
        "valueString": text,
    }
    if _has_required(oid):
        observation["id"] = make_resource_id(oid, "obs", force_prefix=True)
    return observation


def build_condition(rep_data: Dict[str, Any], pid: str, flat: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not _has_required(pid):
        return None
    summary_text = rep_data.get("Summary_clean") or rep_data.get("Observation_clean") or rep_data.get("Report_clean")
    if not _has_required(summary_text):
        return None
    cid = rep_data.get("AccessionNumber") or pid
    condition: Dict[str, Any] = {
        "resourceType": "Condition",
        "code": {"text": str(summary_text)[:200]},
        "subject": {"reference": f"Patient/{pid}"},
        "clinicalStatus": {
            "coding": [{
                "system": "http://terminology.hl7.org/CodeSystem/condition-clinical",
                "code": "active",
            }]
        },
    }
    if _has_required(cid):
        condition["id"] = make_resource_id(cid, "cond", force_prefix=True)
    onset = flat.get("Extraction_Timestamp")
    if _has_required(onset):
        condition["onsetDateTime"] = to_iso(str(onset))
    return condition


def build_practitioner(rep_data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    name = rep_data.get("Report_validated_by")
    if not _has_required(name):
        return None
    pid = make_resource_id(str(name), "practitioner")
    return {
        "resourceType": "Practitioner",
        "id": pid,
        "name": [{"text": str(name)}],
    }


def build_organization(rep_data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    name = rep_data.get("Report_validated_institution") or rep_data.get("Report_Organization")
    if not _has_required(name):
        return None
    oid = make_resource_id(str(name), "organization")
    return {
        "resourceType": "Organization",
        "id": oid,
        "name": str(name),
    }


def build_document_reference(rep_data: Dict[str, Any], pid: str) -> Optional[Dict[str, Any]]:
    if not _has_required(pid):
        return None
    rtf = rep_data.get("Report")
    if not _has_required(rtf):
        return None
    did = rep_data.get("AccessionNumber") or pid
    doc: Dict[str, Any] = {
        "resourceType": "DocumentReference",
        "status": "current",
        "subject": {"reference": f"Patient/{pid}"},
    }
    if _has_required(did):
        doc["id"] = make_resource_id(did, "docref", force_prefix=True)
    b64 = base64.b64encode(str(rtf).encode("utf-8", errors="ignore")).decode("ascii")
    doc["content"] = [{
        "attachment": {
            "contentType": "application/rtf",
            "data": b64,
        }
    }]
    return doc


def _generate_summaries(observation: str, report: str, params: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    text = " ".join([t for t in (observation, report) if t])
    if not text:
        return None
    try:
        from pipeline import summarization
        return summarization.summarize(text, **params)
    except Exception as exc:
        import logging
        logging.getLogger("pipeline.fhir").warning(
            f"Summarization falhou: {type(exc).__name__}: {exc}"
        )
        return None


def build_resources(raw: Dict[str, Any], summary_params: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
    pacs = raw.get("PACS_Report") or raw.get("Report") or raw
    if not isinstance(pacs, dict):
        return []

    flat = flatten(pacs)
    for key in ("Report.Observation", "Report.Report"):
        if key in flat:
            flat[f"{key}_clean"] = clean_rtf(flat[key])

    sections = extract_sections(flat, ("Patient", "Encounter", "Report"))
    pat = build_patient(sections["Patient"])
    if not pat:
        return []
    pid = pat["id"]
    rep = sections["Report"]

    rep["Observation_clean"] = flat.get("Report.Observation_clean")
    rep["Report_clean"] = flat.get("Report.Report_clean")

    resources: List[Dict[str, Any]] = [
        pat,
        build_encounter(sections["Encounter"], rep, pid, flat),
        build_diagnostic_report(rep, pid, flat),
        build_imaging_study(rep, pid, flat),
        build_procedure(rep, pid, flat),
        build_observation(rep, pid),
        build_condition(rep, pid, flat),
        build_practitioner(rep),
        build_organization(rep),
        build_document_reference(rep, pid),
    ]

    if summary_params:
        sums = _generate_summaries(rep.get("Observation_clean", ""), rep.get("Report_clean", ""), summary_params)
        if sums:
            resources.append({
                "resourceType": "Observation",
                "id": f"summary-{pid}",
                "status": "final",
                "subject": {"reference": f"Patient/{pid}"},
                "code": {"text": "Report Summary"},
                "component": [
                    {"code": {"text": "Extractive"}, "valueString": " ".join(sums.get("extractive", []))},
                    {"code": {"text": "Abstractive"}, "valueString": sums.get("abstractive", "")},
                ],
            })

    return [r for r in resources if r]
