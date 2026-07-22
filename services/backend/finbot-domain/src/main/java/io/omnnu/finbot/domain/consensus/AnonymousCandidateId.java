package io.omnnu.finbot.domain.consensus;

import io.omnnu.finbot.domain.shared.DomainText;

/**
 * Opaque anonymous candidate identifier used in double-blind social choice.
 *
 * <p>Must not encode seat, model, provider, or node identity.
 */
public record AnonymousCandidateId(String value) {
    public AnonymousCandidateId {
        value = DomainText.required(value, "anonymousCandidateId", 80);
    }
}
