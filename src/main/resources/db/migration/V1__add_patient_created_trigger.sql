-- Trigger Function: notify_patient_created
-- Sends a pg_notify event to channel 'patient_created' with patient internal res_id (PID)
CREATE OR REPLACE FUNCTION notify_patient_created()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.res_type = 'Patient' THEN
        PERFORM pg_notify('patient_created', NEW.res_id::text);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger: trg_patient_created
-- Automatically fires AFTER INSERT on hfj_resource table
DROP TRIGGER IF EXISTS trg_patient_created ON hfj_resource;
CREATE TRIGGER trg_patient_created
AFTER INSERT ON hfj_resource
FOR EACH ROW
EXECUTE FUNCTION notify_patient_created();
