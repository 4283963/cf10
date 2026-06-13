package com.tcm.dispenser.service;

import com.tcm.dispenser.model.Prescription;
import com.tcm.dispenser.model.PrescriptionItem;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PrescriptionLoader {

    public Prescription loadFromFile(String filePath) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return parseJson(sb.toString());
    }

    public Prescription parseJson(String json) {
        Prescription prescription = new Prescription();
        JsonParser parser = new JsonParser(json);

        Map<String, Object> root = parser.parseObject();

        if (root.containsKey("prescriptionId")) {
            prescription.setPrescriptionId((String) root.get("prescriptionId"));
        }
        if (root.containsKey("patientName")) {
            prescription.setPatientName((String) root.get("patientName"));
        }
        if (root.containsKey("patientId")) {
            prescription.setPatientId((String) root.get("patientId"));
        }
        if (root.containsKey("doctorName")) {
            prescription.setDoctorName((String) root.get("doctorName"));
        }
        if (root.containsKey("department")) {
            prescription.setDepartment((String) root.get("department"));
        }
        if (root.containsKey("createTime")) {
            prescription.setCreateTime((String) root.get("createTime"));
        }
        if (root.containsKey("dosageCount")) {
            Object val = root.get("dosageCount");
            if (val instanceof Number) {
                prescription.setDosageCount(((Number) val).intValue());
            }
        }

        if (root.containsKey("items")) {
            @SuppressWarnings("unchecked")
            List<Object> itemsList = (List<Object>) root.get("items");
            for (Object itemObj : itemsList) {
                @SuppressWarnings("unchecked")
                Map<String, Object> itemMap = (Map<String, Object>) itemObj;
                PrescriptionItem item = new PrescriptionItem();

                if (itemMap.containsKey("binId")) {
                    Object val = itemMap.get("binId");
                    if (val instanceof Number) item.setBinId(((Number) val).intValue());
                }
                if (itemMap.containsKey("medicineName")) {
                    item.setMedicineName((String) itemMap.get("medicineName"));
                }
                if (itemMap.containsKey("medicineCode")) {
                    item.setMedicineCode((String) itemMap.get("medicineCode"));
                }
                if (itemMap.containsKey("dosageGrams")) {
                    Object val = itemMap.get("dosageGrams");
                    if (val instanceof Number) item.setDosageGrams(((Number) val).doubleValue());
                }

                prescription.addItem(item);
            }
        }

        return prescription;
    }

    private static class JsonParser {
        private final String json;
        private int pos;

        JsonParser(String json) {
            this.json = json;
            this.pos = 0;
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            skipWhitespace();
            expect('{');
            skipWhitespace();

            if (peek() == '}') {
                pos++;
                return map;
            }

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                if (peek() == '}') {
                    pos++;
                    break;
                }
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            expect('[');
            skipWhitespace();

            if (peek() == ']') {
                pos++;
                return list;
            }

            while (true) {
                skipWhitespace();
                list.add(parseValue());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                if (peek() == ']') {
                    pos++;
                    break;
                }
            }
            return list;
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            if (c == '"') return parseString();
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            return parseNumber();
        }

        String parseString() {
            skipWhitespace();
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < json.length()) {
                char c = json.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    if (pos < json.length()) {
                        char escaped = json.charAt(pos++);
                        switch (escaped) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u':
                                if (pos + 4 <= json.length()) {
                                    String hex = json.substring(pos, pos + 4);
                                    sb.append((char) Integer.parseInt(hex, 16));
                                    pos += 4;
                                }
                                break;
                            default: sb.append(escaped);
                        }
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Number parseNumber() {
            skipWhitespace();
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < json.length() && Character.isDigit(json.charAt(pos))) pos++;
            boolean isDecimal = false;
            if (pos < json.length() && json.charAt(pos) == '.') {
                isDecimal = true;
                pos++;
                while (pos < json.length() && Character.isDigit(json.charAt(pos))) pos++;
            }
            if (pos < json.length() && (json.charAt(pos) == 'e' || json.charAt(pos) == 'E')) {
                isDecimal = true;
                pos++;
                if (pos < json.length() && (json.charAt(pos) == '+' || json.charAt(pos) == '-')) pos++;
                while (pos < json.length() && Character.isDigit(json.charAt(pos))) pos++;
            }
            String numStr = json.substring(start, pos);
            if (isDecimal) return Double.parseDouble(numStr);
            long longVal = Long.parseLong(numStr);
            if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) return (int) longVal;
            return longVal;
        }

        Boolean parseBoolean() {
            if (json.startsWith("true", pos)) { pos += 4; return true; }
            if (json.startsWith("false", pos)) { pos += 5; return false; }
            throw new RuntimeException("Invalid boolean at position " + pos);
        }

        Object parseNull() {
            if (json.startsWith("null", pos)) { pos += 4; return null; }
            throw new RuntimeException("Invalid null at position " + pos);
        }

        void skipWhitespace() {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) pos++;
        }

        char peek() {
            if (pos >= json.length()) throw new RuntimeException("Unexpected end of JSON");
            return json.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) throw new RuntimeException("Expected '" + c + "' at position " + pos + " but found '" + peek() + "'");
            pos++;
        }
    }
}
