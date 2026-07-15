package codes.yousef.aether.cli.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class IdentityCliCommandParserTest {
    @Test
    fun `parses every identity command`() {
        val login = assertIs<IdentityCliCommand.Login>(
            IdentityCliCommandParser.parse(
                listOf("auth", "login", "--server", "https://identity.example/", "--scope", "a,b", "--scope", "c", "--no-store"),
            ),
        )
        assertEquals("https://identity.example", login.server)
        assertEquals(setOf("a", "b", "c"), login.scopes)
        assertEquals(true, login.noStore)

        assertIs<IdentityCliCommand.WhoAmI>(IdentityCliCommandParser.parse(listOf("auth", "whoami")))
        assertIs<IdentityCliCommand.OrganizationList>(IdentityCliCommandParser.parse(listOf("auth", "org", "list")))
        val use = assertIs<IdentityCliCommand.OrganizationUse>(
            IdentityCliCommandParser.parse(listOf("auth", "org", "use", ORG_ID)),
        )
        assertEquals(ORG_ID, use.organizationId)
        assertIs<IdentityCliCommand.Logout>(IdentityCliCommandParser.parse(listOf("auth", "logout")))
        assertIs<IdentityCliCommand.Help>(IdentityCliCommandParser.parse(listOf("auth", "--help")))
    }

    @Test
    fun `rejects unsafe servers malformed IDs flags and scopes`() {
        assertFailsWith<CliUsageException> {
            IdentityCliCommandParser.parse(listOf("auth", "login", "--server", "http://identity.example"))
        }
        assertFailsWith<CliUsageException> {
            IdentityCliCommandParser.parse(listOf("auth", "org", "use", "org-one"))
        }
        assertFailsWith<CliUsageException> {
            IdentityCliCommandParser.parse(listOf("auth", "whoami", "--no-store"))
        }
        assertFailsWith<CliUsageException> {
            IdentityCliCommandParser.parse(listOf("auth", "login", "--scope", "spaces are split", "--scope", "bad/scope"))
        }
    }

    @Test
    fun `allows HTTP only on loopback development servers`() {
        val command = assertIs<IdentityCliCommand.Login>(
            IdentityCliCommandParser.parse(listOf("auth", "login", "--server", "http://[::1]:8080")),
        )
        assertEquals("http://[::1]:8080", command.server)
    }

    private companion object {
        const val ORG_ID = "018f0f6e-7b20-7abc-8def-0123456789ab"
    }
}
