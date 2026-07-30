package com.taxiapp.server.service

import jakarta.mail.internet.MimeMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class EmailService(
    private val mailSender: JavaMailSender
) {

    @Async
fun sendDriverApprovalEmail(toEmail: String, driverName: String) {
    try {
        val message: MimeMessage = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, true, "UTF-8")

        helper.setTo(toEmail)
        helper.setSubject("Unit Driver: Вашу заявку успішно схвалено")

        val htmlContent = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <link href="https://fonts.googleapis.com/css2?family=Montserrat:wght@400;500;600;700;800&display=swap" rel="stylesheet">
                <style>
                    body, table, td, p, a, li, div {
                        font-family: 'Montserrat', Arial, sans-serif !important;
                    }
                </style>
            </head>
            <body style="margin: 0; padding: 0; background-color: #18191A; font-family: 'Montserrat', Arial, sans-serif; color: #FFFFFF;">
                <!-- ОСНОВНИЙ ФОН З ДВОМА М'ЯКИМИ БІРЮЗОВИМИ ГРАДІЄНТАМИ (ПРАВИЙ ВЕРХНІЙ ТА ЛІВИЙ НИЖНІЙ) -->
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background-color: #18191A; background-image: radial-gradient(circle at 100% 0%, rgba(51, 204, 161, 0.18) 0%, transparent 40%), radial-gradient(circle at 0% 100%, rgba(51, 204, 161, 0.15) 0%, transparent 40%); padding: 40px 10px;">
                    <tr>
                        <td align="center">
                            <!-- ЧИСТА КАРТКА ЛИСТА -->
                            <table role="presentation" width="100%" style="max-width: 560px; background-color: #222426; border-radius: 16px; border: 1px solid rgba(117, 120, 138, 0.3); overflow: hidden; box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5);">
                                
                                <!-- HEADER / BRAND -->
                                <tr>
                                    <td style="padding: 32px 32px 20px 32px; border-bottom: 1px solid rgba(117, 120, 138, 0.2); background-color: #222426;">
                                        <div style="font-size: 22px; font-weight: 800; letter-spacing: 0.5px; color: #33CCA1;">
                                            Unit <span style="color: #FFFFFF; font-weight: 400;">Driver</span>
                                        </div>
                                    </td>
                                </tr>

                                <!-- BODY CONTENT -->
                                <tr>
                                    <td style="padding: 32px; background-color: #222426;">
                                        <!-- ЗАГОЛОВОК -->
                                        <div style="font-size: 12px; font-weight: 700; color: #33CCA1; text-transform: uppercase; letter-spacing: 1.2px; margin-bottom: 12px;">
                                            Заявку підтверджено
                                        </div>

                                        <!-- ПРИВІТАННЯ ТА ІМ'Я -->
                                        <h1 style="margin: 0 0 6px 0; font-size: 24px; font-weight: 700; color: #FFFFFF; letter-spacing: -0.3px;">
                                            Вітаємо в команді!
                                        </h1>
                                        <div style="margin: 0 0 20px 0; font-size: 20px; font-weight: 600; color: #33CCA1;">
                                            $driverName
                                        </div>

                                        <!-- ОПИСОВИЙ ТЕКСТ -->
                                        <p style="margin: 0 0 24px 0; font-size: 15px; line-height: 1.6; color: #E2E8F0;">
                                            Ваші документи та автомобіль успішно пройшли перевірку диспетчерською службою Unit. Ваш акаунт переведено в активний стан.
                                        </p>

                                        <!-- INSTRUCTION BOX -->
                                        <div style="background-color: #18191A; border: 1px solid rgba(117, 120, 138, 0.25); border-radius: 12px; padding: 22px; margin-bottom: 28px;">
                                            <div style="font-size: 13px; font-weight: 700; color: #33CCA1; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 14px;">
                                                Порядок дій для початку роботи:
                                            </div>
                                            <ol style="margin: 0; padding: 0; list-style-position: inside; font-size: 14px; line-height: 1.8; color: #E2E8F0;">
                                                <li style="margin-bottom: 6px;">Авторизуйтесь у мобільному додатку <strong style="color: #FFFFFF; font-weight: 600;">Unit Driver</strong> за вашим номером телефону.</li>
                                                <li style="margin-bottom: 6px;">Перевірте налаштування призначених тарифів у профілі.</li>
                                                <li>Виходьте на лінію для прийому перших замовлень.</li>
                                            </ol>
                                        </div>

                                        <p style="margin: 0; font-size: 14px; line-height: 1.6; color: #E2E8F0;">
                                            З повагою,<br>
                                            <strong style="color: #FFFFFF; font-weight: 600;">Служба підтримки Unit Driver</strong>
                                        </p>
                                    </td>
                                </tr>

                                <!-- FOOTER -->
                                <tr>
                                    <td style="padding: 20px 32px; background-color: #18191A; border-top: 1px solid rgba(117, 120, 138, 0.2); text-align: center; font-size: 12px; color: #94A3B8;">
                                        Це автоматичне повідомлення. Будь ласка, не відповідайте на цей лист.
                                    </td>
                                </tr>

                            </table>
                        </td>
                    </tr>
                </table>
            </body>
            </html>
        """.trimIndent()

        helper.setText(htmlContent, true)
        mailSender.send(message)
    } catch (e: Exception) {
        println("Помилка відправки Email водієві: ${e.message}")
    }
}
}