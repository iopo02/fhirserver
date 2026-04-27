from flask import Flask, jsonify
import requests

app = Flask(__name__)
HAPI_URL = "http://localhost:8080/fhir/Patient"

@app.route('/summary', methods=['GET'])
def get_summary():
    # Pedimos apenas o total, o que é muito mais rápido que pedir os dados
    response = requests.get(f"{HAPI_URL}?_summary=count")
    data = response.json()
    
    return jsonify({
        "total_pacientes": data.get("total", 0),
        "status": "success",
        "timestamp": "2026-04-21"
    })

if __name__ == '__main__':
    app.run(port=5000)
    