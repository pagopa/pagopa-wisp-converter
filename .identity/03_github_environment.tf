resource "github_repository_environment" "github_repository_environment" {
  environment = var.env
  repository  = local.github.repository
  # filter teams reviewers from github_organization_teams
  # if reviewers_teams is null no reviewers will be configured for environment
  dynamic "reviewers" {
    for_each = (var.github_repository_environment.reviewers_teams == null || var.env_short != "p" ? [] : [1])
    content {
      teams = matchkeys(
        data.github_organization_teams.all.teams.*.id,
        data.github_organization_teams.all.teams.*.name,
        var.github_repository_environment.reviewers_teams
      )
    }
  }
  deployment_branch_policy {
    protected_branches     = var.github_repository_environment.protected_branches
    custom_branch_policies = var.github_repository_environment.custom_branch_policies
  }
}

locals {
  env_secrets = {
    "CD_CLIENT_ID" : data.azurerm_user_assigned_identity.identity_cd.client_id,
    "CI_CLIENT_ID" : var.env_short != "p" ? data.azurerm_user_assigned_identity.identity_ci[0].client_id : "",
    "TENANT_ID" : data.azurerm_client_config.current.tenant_id,
    "SUBSCRIPTION_ID" : data.azurerm_subscription.current.subscription_id
    "INTEGRATION_TEST_GPD_SUBSCRIPTION_KEY" : var.env_short != "p" ? data.azurerm_key_vault_secret.integration_test_gpd_subscription_key[0].value : ""
    "INTEGRATION_TEST_NODO_SUBSCRIPTION_KEY" : var.env_short != "p" ? data.azurerm_key_vault_secret.integration_test_nodo_subscription_key[0].value : ""
    "INTEGRATION_TEST_FORWARDER_SUBSCRIPTION_KEY" : var.env_short != "p" ? data.azurerm_key_vault_secret.integration_test_forwarder_subscription_key[0].value : ""
    "INTEGRATION_TEST_TECHNICALSUPPORT_SUBSCRIPTION_KEY" : var.env_short != "p" ? data.azurerm_key_vault_secret.integration_test_technicalsupport_subscription_key[0].value : ""
    "INTEGRATION_TEST_CHANNEL_WISP_PASSWORD" : var.env_short != "p" ? data.azurerm_key_vault_secret.integration_test_channel_wisp_password[0].value : ""
    "INTEGRATION_TEST_CHANNEL_WFESP_PASSWORD" : var.env_short != "p" ? data.azurerm_key_vault_secret.integration_test_channel_wfesp_password[0].value : ""
    "INTEGRATION_TEST_CHANNEL_CHECKOUT_PASSWORD" : var.env_short != "p" ? data.azurerm_key_vault_secret.integration_test_channel_checkout_password[0].value : ""
    "INTEGRATION_TEST_CHANNEL_PAYMENT_PASSWORD" : var.env_short != "p" ? data.azurerm_key_vault_secret.integration_test_channel_payment_password[0].value : ""
    "INTEGRATION_TEST_STATION_WISP_PASSWORD" : var.env_short != "p" ? data.azurerm_key_vault_secret.integration_test_station_wisp_password[0].value : "",
    "REPORT_SLACK_WEBHOOK_URL" : data.azurerm_key_vault_secret.report_generation_slack_webhook_url.value,
    "REPORT_DATAEXPLORER_CLIENT_ID" : data.azurerm_key_vault_secret.report_generation_dataexplorer_clientid.value,
    "REPORT_DATAEXPLORER_CLIENT_SECRET" : data.azurerm_key_vault_secret.report_generation_dataexplorer_clientsecret.value,
    "REPORT_DATABASE_KEY" : data.azurerm_key_vault_secret.report_generation_database_key.value,
    "REPORT_APICONFIG_CACHE_SUBKEY" : data.azurerm_key_vault_secret.report_generation_apiconfigcache_subkey.value,
  }
  env_variables = {
    "CONTAINER_APP_ENVIRONMENT_NAME" : local.container_app_environment.name,
    "CONTAINER_APP_ENVIRONMENT_RESOURCE_GROUP_NAME" : local.container_app_environment.resource_group,
    "CLUSTER_NAME" : local.aks_cluster.name,
    "CLUSTER_RESOURCE_GROUP" : local.aks_cluster.resource_group_name,
    "DOMAIN" : local.domain,
    "NAMESPACE" : local.domain,
    "INTEGRATION_TEST_STORAGE_ACCOUNT_NAME" : local.integration_test.storage_account_name
    "INTEGRATION_TEST_REPORTS_FOLDER" : local.integration_test.reports_folder,
    "REPORT_DATAEXPLORER_URL": local.report_generation.dataexplorer_url,
    "REPORT_DATABASE_URL": local.report_generation.database_url,
    "REPORT_DATABASE_REGION": local.report_generation.database_region,
  }
  repo_secrets = {
    "SONAR_TOKEN" : data.azurerm_key_vault_secret.key_vault_sonar.value,
    "BOT_TOKEN_GITHUB" : data.azurerm_key_vault_secret.key_vault_bot_token.value,
    "CUCUMBER_PUBLISH_TOKEN" : data.azurerm_key_vault_secret.key_vault_cucumber_token.value,
    "SUBKEY" : data.azurerm_key_vault_secret.key_vault_integration_test_subkey.value,
  }
}


###############
# ENV Secrets #
###############

resource "github_actions_environment_secret" "github_environment_runner_secrets" {
  for_each        = local.env_secrets
  repository      = local.github.repository
  environment     = var.env
  secret_name     = each.key
  plaintext_value = each.value
}

#################
# ENV Variables #
#################


resource "github_actions_environment_variable" "github_environment_runner_variables" {
  for_each      = local.env_variables
  repository    = local.github.repository
  environment   = var.env
  variable_name = each.key
  value         = each.value
}

#############################
# Secrets of the Repository #
#############################


resource "github_actions_secret" "repo_secrets" {
  for_each        = local.repo_secrets
  repository      = local.github.repository
  secret_name     = each.key
  plaintext_value = each.value
}

############
## Labels ##
############
resource "github_issue_label" "patch" {
  repository = local.github.repository
  name       = "patch"
  color      = "FF0000"
}

resource "github_issue_label" "ignore_for_release" {
  repository = local.github.repository
  name       = "ignore-for-release"
  color      = "008000"
}
