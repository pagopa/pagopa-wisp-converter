locals {
  github = {
    org        = "pagopa"
    repository = "pagopa-wisp-converter"
  }

  prefix         = "pagopa"
  domain         = "nodo"
  location_short = "weu"
  product        = "${var.prefix}-${var.env_short}"

  app_name = "github-${local.github.org}-${local.github.repository}-${var.prefix}-${local.domain}-${var.env}-aks"

  integration_test = {
    storage_account_name = "${local.prefix}${var.env_short}${local.location_short}sharedtstdtsa"
    storage_account_rg   = "${local.prefix}-${var.env_short}-${local.location_short}-shared-tst-dt-rg"
    reports_folder       = local.github.repository
  }
  
  aks_cluster = {
    name                = "${local.product}-${local.location_short}-${var.env}-aks"
    resource_group_name = "${local.product}-${local.location_short}-${var.env}-aks-rg"
  }

  container_app_environment = {
    name           = "${local.prefix}-${var.env_short}-${local.location_short}-github-runner-cae",
    resource_group = "${local.prefix}-${var.env_short}-${local.location_short}-github-runner-rg",
  }

  report_generation = {
    dataexplorer_url  = "https://${local.prefix}${var.env_short}dataexplorer.westeurope.kusto.windows.net/"
    database_url      = "https://${local.prefix}-${var.env_short}-${local.location_short}-nodo-wispconv-cosmos-account.documents.azure.com:443/"
    database_region   = var.env_short == "p" ? "North Europe" : "West Europe"
  }
}

variable "env" {
  type = string
}

variable "env_short" {
  type = string
}

variable "prefix" {
  type    = string
  default = "pagopa"
  validation {
    condition = (
    length(var.prefix) <= 6
    )
    error_message = "Max length is 6 chars."
  }
}

variable "github_repository_environment" {
  type = object({
    protected_branches     = bool
    custom_branch_policies = bool
    reviewers_teams        = list(string)
  })
  description = "GitHub Continuous Integration roles"
  default     = {
    protected_branches     = false
    custom_branch_policies = true
    reviewers_teams        = ["pagopa-team-core"]
  }
}