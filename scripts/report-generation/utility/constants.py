class Constants:

    # ================================================
    # Database constants
    # ================================================

    # constant for database name
    DATABASE_NAME = "wispconverter"

    # constant for container name for "configuration" data
    CONFIGURATION_CONTAINER_NAME = "configuration"

    # constant for container name for "re" data
    RE_CONTAINER_NAME = "re"

    # constant for container name for "receipt-rt" data
    RECEIPTS_RT_CONTAINER_NAME = "receipts-rt"

    # constant for container name for "reports" data
    REPORTS_CONTAINER_NAME = "reports"

    # constant for container name for "data" data
    TRIGGER_PRIMITIVE_CONTAINER_NAME = "data"

    
    # ================================================
    # Trigger primitive related constants
    # ================================================

    # constant for primitive action on "nodoInviaCarrelloRPT"
    NODO_INVIA_CARRELLO_RPT = "nodoInviaCarrelloRPT"

    # constant for primitive action on "nodoInviaRPT"
    NODO_INVIA_RPT = "nodoInviaRPT"


    # ================================================
    # Report-related constants
    # ================================================

    # constant for daily report type
    DAILY = "daily"

    # constant for title of daily report
    DAILY_REPORT_TITLE = "Report giornaliero [{}]"

    # constant for monthly report type
    MONTHLY = "monthly"

    # constant for title of monthly report
    MONTHLY_REPORT_TITLE = "Report mensile [{}]"

    # constant for no data in report
    NODATA = "NO-DATA"

    # constant for summary of Slack notification report
    SLACK_MSG_SUMMARY = "Un nuovo report per Dismissione WISP è disponibile!"

    # constant for weekly report type
    WEEKLY = "weekly"

    # constant for title of weekly report
    WEEKLY_REPORT_TITLE = "Report settimanale [{}]"


    # ================================================
    # Other constants
    # ================================================

    # constant for file name that includes parameters
    PARAMETERS_FILENAME = "config.json"

    # constant for segregation code
    SEGREGATION_CODE_FOR_WISP = 51

    # constant for wildcard character
    WILDCARD_CHARACTER = "*"