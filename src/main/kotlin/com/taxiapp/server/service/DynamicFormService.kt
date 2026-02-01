package com.taxiapp.server.service

import com.taxiapp.server.repository.FormTemplateRepository
import org.springframework.stereotype.Service
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

@Service
class DynamicFormService(
    private val templateRepository: FormTemplateRepository
) {
    private val mapper = ObjectMapper()

    fun generateHtmlForm(formKey: String, postUrl: String, token: String): String {
        // Если шаблона нет, создадим дефолтный, чтобы не падало
        val template = templateRepository.findById(formKey).orElseThrow { RuntimeException("Form not found") }
        
        // Безопасный парсинг JSON
        val fields = try {
            mapper.readValue<List<Map<String, Any>>>(template.schemaJson)
        } catch (e: Exception) {
            emptyList()
        }

        val fieldsHtml = fields.joinToString("\n") { field ->
            val type = field["type"] as? String ?: "text"
            val name = field["name"] as? String ?: "unknown"
            val label = field["label"] as? String ?: name
            val required = if (field["required"] == true) "required" else ""
            
            // Важно: добавляем класс 'json-field', чтобы JS знал, что это поле надо упаковать
            when (type) {
                "text" -> """
                    <div class="form-group">
                        <label>$label</label>
                        <input type="text" name="$name" class="form-control json-field" $required placeholder="$label">
                    </div>
                """.trimIndent()
                "photo" -> """
                    <div class="form-group">
                        <label>$label</label>
                        <div class="file-input-wrapper">
                            <button type="button" onclick="document.getElementById('$name').click()">📸 Загрузить фото</button>
                            <input type="file" id="$name" name="$name" accept="image/*" $required onchange="previewImage(this, '$name')">
                            <div id="preview-$name" class="preview-box"></div>
                        </div>
                    </div>
                """.trimIndent()
                "select" -> {
                     val optionsList = field["options"] as? List<String> ?: emptyList()
                     val options = optionsList.joinToString("") { "<option value='$it'>$it</option>" }
                     """
                     <div class="form-group">
                        <label>$label</label>
                        <select name="$name" class="form-control json-field" $required>
                            $options
                        </select>
                     </div>
                     """
                }
                else -> ""
            }
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: 'Segoe UI', sans-serif; padding: 20px; background: #fff; color: #333; }
                    .form-group { margin-bottom: 20px; }
                    label { display: block; margin-bottom: 8px; font-weight: 600; font-size: 14px; }
                    .form-control { width: 100%; padding: 12px; border: 1px solid #ccc; border-radius: 8px; box-sizing: border-box; font-size: 16px; }
                    button[type="submit"] { width: 100%; padding: 16px; background: #000; color: #fff; border: none; border-radius: 12px; font-size: 18px; font-weight: bold; margin-top: 30px; cursor: pointer; }
                    
                    /* Стили для фото */
                    .file-input-wrapper button { width: 100%; background: #f0f0f0; color: #333; border: 1px dashed #999; padding: 12px; border-radius: 8px; font-size: 14px; }
                    .file-input-wrapper input { display: none; }
                    .preview-box { margin-top: 10px; width: 100%; height: 150px; background-size: cover; background-position: center; border-radius: 8px; display: none; border: 1px solid #ddd; }
                </style>
                <script>
                    function previewImage(input, id) {
                        if (input.files && input.files[0]) {
                            var reader = new FileReader();
                            reader.onload = function(e) {
                                var preview = document.getElementById('preview-' + id);
                                preview.style.backgroundImage = 'url(' + e.target.result + ')';
                                preview.style.display = 'block';
                            }
                            reader.readAsDataURL(input.files[0]);
                        }
                    }

                    // ГЛАВНАЯ ЛОГИКА: Сбор данных в JSON перед отправкой
                    function prepareAndSubmit(event) {
                        event.preventDefault(); // Остановить стандартную отправку
                        
                        var dataObj = {};
                        
                        // 1. Собираем все поля с классом json-field (текст, списки)
                        var inputs = document.querySelectorAll('.json-field');
                        inputs.forEach(function(input) {
                            dataObj[input.name] = input.value;
                        });

                        // 2. Кладем JSON в скрытое поле 'data'
                        document.getElementById('hidden-json-data').value = JSON.stringify(dataObj);

                        // 3. Отправляем форму (теперь там есть и файлы, и токен, и data)
                        event.target.submit();
                    }
                </script>
            </head>
            <body>
                <h2 style="text-align:center; margin-bottom:20px;">Регистрация авто</h2>
                
                <form action="$postUrl" method="POST" enctype="multipart/form-data" onsubmit="prepareAndSubmit(event)">
                    
                    $fieldsHtml
                    
                    <input type="hidden" name="token" value="$token"> 
                    
                    <input type="hidden" name="data" id="hidden-json-data"> 

                    <button type="submit">Отправить заявку</button>
                </form>
            </body>
            </html>
        """.trimIndent()
    }
}