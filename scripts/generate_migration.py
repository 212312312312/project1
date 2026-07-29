import pandas as pd
import re

excel_path = "Класифікатор авто _ Uklon.xlsx"

# Загружаем рабочий лист классификатора
df = pd.read_excel(excel_path, sheet_name='Класифікатор (from 06.05.2025)', skiprows=8)
df.columns = ['make', 'model', 'generation', 'segment', 'year_from', 'year_to', 'grade_a', 'grade_b']
df_clean = df.iloc[1:].copy()

def escape_sql(val):
    if pd.isna(val) or str(val).strip() in ['-', 'nan', 'None', '']:
        return "NULL"
    cleaned = str(val).strip().replace("'", "''")
    return f"'{cleaned}'"

def parse_year(val):
    if pd.isna(val) or str(val).strip() in ['-', 'nan', 'None', '']:
        return "NULL"
    match = re.search(r'\d{4}', str(val).strip())
    return match.group(0) if match else "NULL"

def map_status(val):
    val_l = str(val).lower()
    if 'бізнес' in val_l:
        return 'BUSINESS'
    elif 'комфорт' in val_l:
        return 'COMFORT'
    elif 'стандарт' in val_l:
        return 'STANDARD'
    else:
        return 'RESTRICTED'

sql_lines = []
sql_lines.append("-- ========================================================")
sql_lines.append("-- Flyway Migration V2: Car Classifier Tables & Data Seed")
sql_lines.append("-- ========================================================\n")

# 1. Создание таблиц (Увеличили segment до VARCHAR(100))
sql_lines.append("""
CREATE TABLE IF NOT EXISTS cities (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    grade VARCHAR(10) NOT NULL
);

CREATE TABLE IF NOT EXISTS car_brands (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS car_models (
    id BIGSERIAL PRIMARY KEY,
    brand_id BIGINT NOT NULL REFERENCES car_brands(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT uk_brand_model UNIQUE(brand_id, name)
);

CREATE TABLE IF NOT EXISTS car_classifier_rules (
    id BIGSERIAL PRIMARY KEY,
    model_id BIGINT NOT NULL REFERENCES car_models(id) ON DELETE CASCADE,
    generation VARCHAR(255),
    segment VARCHAR(100),
    year_from INT,
    year_to INT,
    status_grade_a VARCHAR(50) NOT NULL,
    status_grade_b VARCHAR(50) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_car_models_brand ON car_models(brand_id);
CREATE INDEX IF NOT EXISTS idx_car_rules_model ON car_classifier_rules(model_id);
""")

# 2. Наполнение городов
sql_lines.append("""
INSERT INTO cities (name, grade) VALUES
('Київ', 'GRADE_A'), ('Львів', 'GRADE_A'), ('Дніпро', 'GRADE_A'), ('Одеса', 'GRADE_A'),
('Хмельницький', 'GRADE_B'), ('Вінниця', 'GRADE_B'), ('Житомир', 'GRADE_B'), ('Полтава', 'GRADE_B'),
('Харків', 'GRADE_B'), ('Черкаси', 'GRADE_B'), ('Чернігів', 'GRADE_B'), ('Рівне', 'GRADE_B'),
('Івано-Франківськ', 'GRADE_B'), ('Луцьк', 'GRADE_B'), ('Тернопіль', 'GRADE_B'), ('Ужгород', 'GRADE_B'),
('Чернівці', 'GRADE_B'), ('Запоріжжя', 'GRADE_B'), ('Кривий Ріг', 'GRADE_B'), ('Кропивницький', 'GRADE_B'),
('Миколаїв', 'GRADE_B'), ('Біла Церква', 'GRADE_B'), ('Камʼянське', 'GRADE_B'), ('Кременчук', 'GRADE_B')
ON CONFLICT (name) DO NOTHING;
""")

# 3. Наполнение марок
makes = sorted(df_clean['make'].astype(str).str.strip().unique())
for make in makes:
    clean_m = make.replace("'", "''")
    sql_lines.append(f"INSERT INTO car_brands (name) VALUES ('{clean_m}') ON CONFLICT (name) DO NOTHING;")

# 4. Наполнение моделей и правил
sql_lines.append("\n-- Insert Models & Rules")
for idx, row in df_clean.iterrows():
    make = str(row['make']).strip().replace("'", "''")
    model = str(row['model']).strip().replace("'", "''")
    gen = escape_sql(row['generation'])
    seg = escape_sql(row['segment'])
    y_from = parse_year(row['year_from'])
    y_to = parse_year(row['year_to'])
    status_a = map_status(row['grade_a'])
    status_b = map_status(row['grade_b'])

    block = f"""DO $$
DECLARE
    v_brand_id BIGINT;
    v_model_id BIGINT;
BEGIN
    SELECT id INTO v_brand_id FROM car_brands WHERE name = '{make}';
    
    INSERT INTO car_models (brand_id, name) 
    VALUES (v_brand_id, '{model}') 
    ON CONFLICT (brand_id, name) DO UPDATE SET name = EXCLUDED.name
    RETURNING id INTO v_model_id;

    INSERT INTO car_classifier_rules (model_id, generation, segment, year_from, year_to, status_grade_a, status_grade_b)
    VALUES (v_model_id, {gen}, {seg}, {y_from}, {y_to}, '{status_a}', '{status_b}');
END $$;"""
    sql_lines.append(block)

output_filename = "V2__init_car_classifier.sql"
with open(output_filename, "w", encoding="utf-8") as f:
    f.write("\n".join(sql_lines))

print(f"✅ Готово! Файл {output_filename} обновлен.")