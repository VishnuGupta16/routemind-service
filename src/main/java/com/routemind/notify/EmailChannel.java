package com.routemind.notify;

import com.routemind.report.GeneratedReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Email delivery.
 *
 * {@code JavaMailSender} is optional on purpose. Without SMTP configured the bean is absent,
 * this channel reports {@link #available()} as false, and sends are logged as SKIPPED with a
 * clear reason. The demo therefore runs end to end — report generated, recipients resolved,
 * routing decided, outcome logged — on a laptop with no mail server, which is exactly the
 * situation a demo is recorded in.
 *
 * Set {@code routemind.notify.dry-run: true} to get the same behaviour with SMTP present:
 * the message is composed and logged in full but not sent.
 */
@Component
public class EmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    private final Optional<JavaMailSender> mailer;
    private final String from;
    private final String subjectPrefix;
    private final boolean dryRun;

    public EmailChannel(Optional<JavaMailSender> mailer,
                        @Value("${routemind.notify.from:routemind@example.com}") String from,
                        @Value("${routemind.notify.subject-prefix:[RouteMind]}") String prefix,
                        @Value("${routemind.notify.dry-run:true}") boolean dryRun) {
        this.mailer = mailer;
        this.from = from;
        this.subjectPrefix = prefix;
        this.dryRun = dryRun;
    }

    public String kind() { return "EMAIL"; }

    public boolean available() { return dryRun || mailer.isPresent(); }

    @Override
    public String send(String target, Recipient to, GeneratedReport r) {
        String subject = "%s %s — %s".formatted(subjectPrefix, r.personaCode().equals("")
                ? "Briefing" : to.personaName(), r.headline());
        String text = compose(to, r);

        if (dryRun || mailer.isEmpty()) {
            log.info("[dry-run] email to {} <{}>\nSubject: {}\n{}",
                    to.displayName(), target, subject, text);
            return dryRun ? null : "no JavaMailSender configured";
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(target);
            msg.setSubject(subject);
            msg.setText(text);
            mailer.get().send(msg);
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * The recommended action goes at the TOP, not the bottom.
     *
     * This is read on a phone. If the one thing the reader is meant to do sits under six
     * sections of numbers, it is not read at all — and the numbers exist to justify the
     * action, not the other way round.
     */
    private String compose(Recipient to, GeneratedReport r) {
        StringBuilder s = new StringBuilder();
        s.append(to.displayName() == null ? "Hello" : "Hi " + to.displayName()).append(",\n\n");
        s.append(r.headline()).append("\n\n");

        if (r.recommendedAction() != null && !r.recommendedAction().isBlank()) {
            s.append("WHAT TO DO\n").append(r.recommendedAction()).append("\n\n");
        }
        s.append(r.body()).append("\n");
        s.append("---\n")
         .append("Prepared for: ").append(to.personaName()).append('\n')
         .append("Period: ").append(r.periodStart()).append(" to ").append(r.periodEnd()).append('\n')
         .append("Severity: ").append(r.severityScore()).append("/100\n")
         .append("Written by: ").append(r.generatedBy())
         .append(" — every figure above is stored with the query that produced it.\n");
        return s.toString();
    }
}
