package dev.jvault.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jvault.api.meta.MetadataController;
import dev.jvault.api.security.Caller;
import dev.jvault.api.security.CallerResolver;
import dev.jvault.authz.Principal;
import dev.jvault.domain.common.Classification;
import dev.jvault.domain.placement.LinkPlacement;
import dev.jvault.domain.placement.PartType;
import dev.jvault.domain.placement.Placement;
import dev.jvault.domain.placement.PlacementPolicy;
import dev.jvault.domain.placement.PolicySelector;
import dev.jvault.domain.placement.PolicySet;
import dev.jvault.domain.placement.SurrogateSpec;
import dev.jvault.jira.gateway.JiraMetadataGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The form definition the SPA is built from.
 *
 * <p>The field list comes from Jira. The two things this endpoint adds are the reason it exists:
 * where each field's value will be stored, and how faithfully jvault can render it.
 *
 * <p>The fixture uses the field types a real team-managed project actually returned, rather than
 * invented ones — including the ranking and team fields that must not be editable.
 */
class MetadataControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEPLOYMENT = "jira-cloud-prod";

    private final AtomicReference<Caller> caller = new AtomicReference<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        caller.set(new Caller(Principal.user("alice"),
                Set.of(Principal.group("sec-responders")), true));

        var controller = new MetadataController(fakeJira(), policies(), callerResolver(),
                DEPLOYMENT, "https://acme.atlassian.net");
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("a field says where its value will be stored, before anyone types into it")
    void fieldsCarryTheirPlacement() throws Exception {
        String body = fields();

        var description = field(body, "description");
        assertThat(description.get("placement").asText()).isEqualTo("EXTERNAL");
        assertThat(description.get("classification").asText()).isEqualTo("RESTRICTED");

        // Finding out afterwards is the kind of surprise that makes people paste sensitive text
        // somewhere else instead.
        var summary = field(body, "summary");
        assertThat(summary.get("placement").asText()).isEqualTo("JIRA");
        assertThat(summary.get("classification").isNull()).isTrue();
    }

    @Test
    @DisplayName("required flags and option lists come through from Jira")
    void jiraMetadataIsPreserved() throws Exception {
        String body = fields();

        assertThat(field(body, "summary").get("required").asBoolean()).isTrue();
        assertThat(field(body, "description").get("required").asBoolean()).isFalse();
        assertThat(field(body, "priority").get("allowedValues")).hasSize(2);
    }

    @Test
    @DisplayName("fields jvault cannot faithfully render are marked read-only, not hidden")
    void unsupportedTypesAreMarkedReadOnly() throws Exception {
        String body = fields();

        // Ranking and team fields are maintained by Jira's own boards; a form that let someone
        // set them by hand would be lying about the effect. Showing them read-only is honest,
        // and much better than a control that accepts input and drops it.
        assertThat(field(body, "customfield_10019").get("supportLevel").asText())
                .isEqualTo("READ_ONLY");
        assertThat(field(body, "customfield_10001").get("supportLevel").asText())
                .isEqualTo("READ_ONLY");
        assertThat(field(body, "customfield_10015").get("supportLevel").asText())
                .isEqualTo("REPRODUCED");
    }

    @Test
    @DisplayName("an unknown custom type falls back to letting Jira validate it")
    void unknownTypesDelegateValidation() throws Exception {
        assertThat(field(fields(), "customfield_99999").get("supportLevel").asText())
                .isEqualTo("DELEGATED_VALIDATION");
    }

    @Test
    @DisplayName("a field with no set operation is read-only whatever its type")
    void unsettableFieldsAreReadOnly() throws Exception {
        // A plain text field, which jvault renders perfectly well, that Jira says cannot be set.
        // The type is not the deciding factor here — Jira's own answer is.
        assertThat(field(fields(), "customfield_10010").get("supportLevel").asText())
                .isEqualTo("READ_ONLY");
    }

    @Test
    @DisplayName("a deployment that reports no operations at all still yields a usable form")
    void absentOperationsAreNotTreatedAsRefusal() throws Exception {
        // Empty means Jira refused the field. Absent means it never spoke. Reading silence as
        // refusal would render every control on the form inert against such a deployment.
        assertThat(field(fields(), "customfield_10011").get("supportLevel").asText())
                .isEqualTo("REPRODUCED");
    }

    @Test
    @DisplayName("a truncated option list says so rather than pretending to be complete")
    void largeOptionListsAreFlagged() throws Exception {
        assertThat(field(fields(), "customfield_88888").get("hasMoreOptions").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the control to render comes from the same table the payload is encoded with")
    void controlsMatchTheEncoding() throws Exception {
        String body = fields();

        // If these two ever disagree, the user fills the form in correctly and Jira rejects it.
        assertThat(field(body, "description").get("control").asText()).isEqualTo("RICH_TEXT");
        assertThat(field(body, "summary").get("control").asText()).isEqualTo("TEXT");
        assertThat(field(body, "priority").get("control").asText()).isEqualTo("SELECT");
        assertThat(field(body, "customfield_10015").get("control").asText()).isEqualTo("DATE");
        assertThat(field(body, "labels").get("control").asText()).isEqualTo("LABELS");
        assertThat(field(body, "assignee").get("control").asText()).isEqualTo("USER");
    }

    @Test
    @DisplayName("a field jvault has no control for is read-only, not a text box")
    void fieldsWithoutAControlAreReadOnly() throws Exception {
        String body = fields();

        // Jira will happily accept both. jvault has no upload control and no role picker, and a
        // text box that takes an attachment is worse than one that admits it cannot.
        assertThat(field(body, "attachment").get("supportLevel").asText()).isEqualTo("READ_ONLY");
        assertThat(field(body, "issuerestriction").get("supportLevel").asText())
                .isEqualTo("READ_ONLY");
    }

    @Test
    @DisplayName("the identity endpoint reports the language Jira's own labels arrive in")
    void identityCarriesTheJiraLocale() throws Exception {
        String body = mvc.perform(get("/api/v1/meta/me"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Jira returns field names in the authenticated account's language and ignores
        // Accept-Language, so with a service account this is everyone's label language whether
        // they chose it or not. The client cannot be honest about that without being told.
        var identity = JSON.readTree(body);
        assertThat(identity.get("jiraLocale").asText()).isEqualTo("en_GB");
        assertThat(identity.get("user").asText()).isEqualTo("alice");
        assertThat(identity.get("jiraAccount").asText()).isEqualTo("Service Account");
        // So a client can link back to the issue: Jira carries a link to the vault, and the
        // vault carries one back.
        assertThat(identity.get("jiraBaseUrl").asText()).isEqualTo("https://acme.atlassian.net");
    }

    @Test
    @DisplayName("a user search narrows to the assignable list when asked")
    void userSearchRespectsAssignable() throws Exception {
        String assignable = mvc.perform(get("/api/v1/meta/projects/KAN/users?query=a"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String everyone = mvc.perform(
                        get("/api/v1/meta/projects/KAN/users?query=a&assignable=false"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // An assignee must be assignable; a reporter can be anyone, and narrowing that list
        // would hide the person who actually reported it.
        assertThat(JSON.readTree(assignable)).hasSize(1);
        assertThat(JSON.readTree(everyone)).hasSize(2);
    }

    @Test
    @DisplayName("a parent field gets an issue picker, with Jira validating the choice")
    void parentIsAnIssuePicker() throws Exception {
        var parent = field(fields(), "parent");

        assertThat(parent.get("control").asText()).isEqualTo("ISSUE");
        // Which issue types may parent which depends on a hierarchy jvault cannot read, so the
        // candidate list is a best effort and Jira has the last word.
        assertThat(parent.get("supportLevel").asText()).isEqualTo("DELEGATED_VALIDATION");
    }

    @Test
    @DisplayName("issue search is served for the project the form is for")
    void issueSearchIsServed() throws Exception {
        String body = mvc.perform(get("/api/v1/meta/projects/KAN/issues?query=payroll"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JSON.readTree(body).get(0).get("key").asText()).isEqualTo("KAN-1");
    }

    @Test
    @DisplayName("a type nothing can parent offers no candidates rather than invalid ones")
    void unparentableTypesOfferNothing() throws Exception {
        String body = mvc.perform(get("/api/v1/meta/projects/KAN/issues?issueType=10001"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Offering a list Jira will refuse is how every ticket with a parent failed: the picker
        // showed tasks as candidate parents of tasks, and Jira rejects that combination.
        assertThat(JSON.readTree(body)).isEmpty();
    }

    @Test
    @DisplayName("an anonymous caller gets 401")
    void anonymousIsRefused() throws Exception {
        caller.set(null);

        mvc.perform(get("/api/v1/meta/projects")).andExpect(status().isUnauthorized());
    }

    // --- fixtures ----------------------------------------------------------------

    private String fields() throws Exception {
        return mvc.perform(get("/api/v1/meta/projects/KAN/issuetypes/10004/fields"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static com.fasterxml.jackson.databind.JsonNode field(String body, String key)
            throws Exception {
        for (var node : JSON.readTree(body).path("fields")) {
            if (key.equals(node.path("key").asText())) {
                return node;
            }
        }
        throw new AssertionError("no field " + key + " in the form definition");
    }

    private static PolicySet policies() {
        return PolicySet.of(List.of(PlacementPolicy.builder()
                .id("sec-description")
                .selector(new PolicySelector(DEPLOYMENT, "KAN", null, PartType.DESCRIPTION, null))
                .placement(Placement.EXTERNAL)
                .classification(Classification.RESTRICTED)
                .storageRoute("fs-local")
                .keyRing("sec-restricted")
                .encryptionRequired(true)
                .surrogate(SurrogateSpec.placeholder("Stored in jvault: {{link}}"))
                .linkPlacements(Set.of(LinkPlacement.REMOTE_LINK))
                .build()));
    }

    /** Shaped after what a real team-managed project returned. */
    private static JiraMetadataGateway fakeJira() {
        return new JiraMetadataGateway() {
            @Override
            public List<Project> projects() {
                return List.of(new Project("10000", "KAN", "My first Jira", "next-gen"));
            }

            @Override
            public CurrentUser currentUser() {
                return new CurrentUser("712020:abc", "Service Account", "en_GB");
            }

            @Override
            public Map<String, String> displayNamesOf(Collection<String> accountIds) {
                return Map.of("5b10a2", "Ada Lovelace");
            }

            @Override
            public List<UserRef> searchUsers(String projectKey, String query, boolean assignable) {
                var everyone = List.of(
                        new UserRef("5b10a2", "Ada Lovelace", "ada@example.com", true),
                        new UserRef("5b10a3", "Grace Hopper", null, true));
                // The assignable list is narrower, which is the distinction under test.
                return assignable ? List.of(everyone.get(0)) : everyone;
            }

            @Override
            public List<IssueRef> searchIssues(String projectKey, String query,
                                               String childIssueTypeId) {
                // An epic parents a task; nothing parents an epic.
                return "10001".equals(childIssueTypeId)
                        ? List.of()
                        : List.of(new IssueRef("KAN-1", "Migrate the payroll export", "Epic"));
            }

            @Override
            public List<IssueType> issueTypes(String projectKey) {
                return List.of(new IssueType("10004", "Task", false, null, 0));
            }

            @Override
            public List<FieldMeta> fields(String projectKey, String issueTypeId) {
                return List.of(
                        new FieldMeta("summary", "Summary", true, "string", null, null,
                                List.of(), false, List.of("set")),
                        new FieldMeta("description", "Description", false, "string", null, null,
                                List.of(), false, List.of("set")),
                        new FieldMeta("attachment", "Attachment", false, "array", "attachment", null,
                                List.of(), false, List.of("set")),
                        new FieldMeta("issuerestriction", "Restrict to", false,
                                "issuerestriction", null, null, List.of(), false, List.of("set")),
                        new FieldMeta("parent", "Parent", false, "issuelink", null, null,
                                List.of(), false, List.of("set")),
                        new FieldMeta("labels", "Labels", false, "array", "string", null,
                                List.of(), false, List.of("set", "add", "remove")),
                        new FieldMeta("assignee", "Assignee", false, "user", null, null,
                                List.of(), false, List.of("set")),
                        new FieldMeta("priority", "Priority", false, "priority", null, null,
                                List.of(new AllowedValue("1", "Highest"),
                                        new AllowedValue("2", "High")), false, List.of("set")),
                        new FieldMeta("customfield_10015", "Start date", false, "date",
                                null, "datepicker", List.of(), false, List.of("set")),
                        new FieldMeta("customfield_10019", "Rank", false, "any",
                                null, "gh-lexo-rank", List.of(), false, List.of("set")),
                        new FieldMeta("customfield_10001", "Team", false, "any",
                                null, "atlassian-team", List.of(), false, List.of("set")),
                        new FieldMeta("customfield_10000", "Development", false, "any",
                                null, "devsummarycf", List.of(), false, List.of()),
                        new FieldMeta("customfield_10010", "Request Type", false, "string",
                                null, "textfield", List.of(), false, List.of()),
                        new FieldMeta("customfield_10011", "Legacy field", false, "string",
                                null, "textfield", List.of(), false, null),
                        new FieldMeta("customfield_99999", "Something new", false, "string",
                                null, "some-app-field", List.of(), false, List.of("set")),
                        new FieldMeta("customfield_88888", "Big select", false, "option",
                                null, "select", List.of(new AllowedValue("1", "one")), true,
                                List.of("set")));
            }
        };
    }

    private CallerResolver callerResolver() {
        return new CallerResolver() {
            @Override
            public Optional<Caller> resolve(HttpServletRequest request) {
                return Optional.ofNullable(caller.get());
            }
        };
    }
}
