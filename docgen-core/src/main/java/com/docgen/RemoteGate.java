package com.docgen;

/**
 * Shared remote-transmission gate: a remote provider is used only when the
 * operator enabled remote transmission and the consent callback approved the
 * concrete destination and payload size. Local providers skip the callback.
 */
final class RemoteGate {
    private RemoteGate() {
    }

    static LLMProvider authorize(
            LLMProviderResolver resolver,
            ProviderSelection selection,
            DocumentationService.RemoteConsent consent,
            int itemCount,
            int characterCount
    ) throws DocumentationService.RemoteConsentDeniedException {
        LLMProvider provider = resolver.create(selection.provider(), selection.model(), selection.allowRemote());
        if (provider.isRemote()) {
            if (!selection.allowRemote()) {
                throw new IllegalArgumentException("The selected provider sends content to a remote service and remote transmission was not enabled.");
            }
            DocumentationService.RemoteTransmission transmission = new DocumentationService.RemoteTransmission(
                    provider.describeDestination(), itemCount, characterCount);
            if (!consent.authorize(transmission)) {
                throw new DocumentationService.RemoteConsentDeniedException();
            }
        }
        return provider;
    }
}
