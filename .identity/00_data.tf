data "azurerm_storage_account" "tf_storage_account" {
  name                = "pagopainfraterraform${var.env}"
  resource_group_name = "io-infra-rg"
}

data "azurerm_resource_group" "dashboards" {
  name = "dashboards"
}

data "azurerm_resource_group" "apim_resource_group" {
  name = "${local.product}-api-rg"
}

data "azurerm_kubernetes_cluster" "aks" {
  name                = local.aks_cluster.name
  resource_group_name = local.aks_cluster.resource_group_name
}

data "github_organization_teams" "all" {
  root_teams_only = true
  summary_only    = true
}

data "azurerm_key_vault" "key_vault" {
  name                = "pagopa-${var.env_short}-kv"
  resource_group_name = "pagopa-${var.env_short}-sec-rg"
}

data "azurerm_key_vault" "domain_key_vault" {
  name                = "pagopa-${var.env_short}-${local.domain}-kv"
  resource_group_name = "pagopa-${var.env_short}-${local.domain}-sec-rg"
}

data "azurerm_key_vault_secret" "key_vault_sonar" {
  name         = "sonar-token"
  key_vault_id = data.azurerm_key_vault.key_vault.id
}

data "azurerm_key_vault_secret" "key_vault_bot_token" {
  name         = "bot-token-github"
  key_vault_id = data.azurerm_key_vault.key_vault.id
}

data "azurerm_key_vault_secret" "key_vault_cucumber_token" {
  name         = "cucumber-token"
  key_vault_id = data.azurerm_key_vault.key_vault.id
}

data "azurerm_key_vault_secret" "key_vault_integration_test_subkey" {
  name         = "integration-test-subkey"
  key_vault_id = data.azurerm_key_vault.key_vault.id
}

data "azurerm_key_vault_secret" "integration_test_gpd_subscription_key" {
  count        = var.env_short == "p" ? 0 : 1
  name         = "integration-test-gpd-subscription-key"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "integration_test_nodo_subscription_key" {
  count        = var.env_short == "p" ? 0 : 1
  name         = "integration-test-nodo-subscription-key"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "integration_test_forwarder_subscription_key" {
  count        = var.env_short == "p" ? 0 : 1
  name         = "integration-test-forwarder-subscription-key"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "integration_test_technicalsupport_subscription_key" {
  count        = var.env_short == "p" ? 0 : 1
  name         = "integration-test-technicalsupport-subscription-key"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "integration_test_channel_wisp_password" {
  count        = var.env_short == "p" ? 0 : 1
  name         = "integration-test-channel-wisp-password"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "integration_test_channel_wfesp_password" {
  count        = var.env_short == "p" ? 0 : 1
  name         = "integration-test-channel-wfesp-password"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "integration_test_channel_checkout_password" {
  count        = var.env_short == "p" ? 0 : 1
  name         = "integration-test-channel-checkout-password"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "integration_test_channel_payment_password" {
  count        = var.env_short == "p" ? 0 : 1
  name         = "integration-test-channel-payment-password"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "integration_test_station_wisp_password" {
  count        = var.env_short == "p" ? 0 : 1
  name         = "integration-test-station-wisp-password"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "report_generation_slack_webhook_url" {
  name         = "report-generation-wisp-slack-webhook-url"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "report_generation_dataexplorer_clientid" {
  name         = "dataexplorer-client-id"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "report_generation_dataexplorer_clientsecret" {
  name         = "dataexplorer-client-secret"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "report_generation_database_key" {
  name         = "cosmosdb-wisp-converter-account-key"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_key_vault_secret" "report_generation_apiconfigcache_subkey" {
  name         = "api-config-cache-subscription-key-string"
  key_vault_id = data.azurerm_key_vault.domain_key_vault.id
}

data "azurerm_user_assigned_identity" "identity_cd" {
  name                = "${local.product}-${local.domain}-01-github-cd-identity"
  resource_group_name = "${local.product}-identity-rg"
}

data "azurerm_user_assigned_identity" "identity_ci" {
  count               = var.env_short == "p" ? 0 : 1
  name                = "${local.product}-${local.domain}-01-github-ci-identity"
  resource_group_name = "${local.product}-identity-rg"
}