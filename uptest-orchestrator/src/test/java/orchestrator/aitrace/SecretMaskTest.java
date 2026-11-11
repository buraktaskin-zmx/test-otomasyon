package orchestrator.aitrace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules moved out of ActionTape, pinned on the cases that shaped them.
 */
class SecretMaskTest {

    private static final String PASSWORD = "123456";
    private final SecretMask mask = SecretMask.of(List.of(PASSWORD, "998877"));

    @Test
    @DisplayName("a password that is a whole JSON argument is masked")
    void masksWholeJsonArgument() {
        String args = "{\"target\":\"e12\",\"value\":\"123456\"}";
        assertThat(mask.maskValues(args)).doesNotContain(PASSWORD).contains("\"***\"");
    }

    @Test
    @DisplayName("the Playwright line the MCP server echoes back is masked too")
    void masksEchoedPlaywrightLine() {
        String result = "### Ran Playwright code\nawait page.locator('#password').fill('123456');";
        assertThat(mask.maskValues(result)).doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("a page value that merely contains the password's digits survives — the 8***7 lesson")
    void keepsPageValueContainingTheDigits() {
        String snapshot = "- cell \"81234567\" [ref=e40]";
        assertThat(mask.maskValues(snapshot)).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("the composed instruction writes the password unquoted; the loose rule catches it")
    void looseRuleMasksUnquotedInstruction() {
        String instruction = "Giriş yap:\n- Şifre = 123456\n- #btnSubmit";
        assertThat(mask.maskText(instruction)).doesNotContain(PASSWORD).contains("- Şifre = ***");
    }

    @Test
    @DisplayName("a secret shorter than four characters, blank or null is ignored")
    void ignoresShortAndBlankSecrets() {
        SecretMask weak = SecretMask.of(Arrays.asList("12", "  ", null, " 4321 "));
        assertThat(weak.secrets()).containsExactly("4321");
        assertThat(weak.maskText("12 ve 4321")).isEqualTo("12 ve ***");
    }

    @Test
    void nullTextAndNoSecretsPassThrough() {
        assertThat(mask.maskValues(null)).isNull();
        assertThat(SecretMask.NONE.maskText("123456")).isEqualTo("123456");
    }
}
