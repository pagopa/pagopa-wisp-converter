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
    # Entity field-related constants
    # ================================================

    # constant for field 'completed'
    COMPLETED_MACROTAG = "completed"

    # constant for field 'not_completed'
    NOT_COMPLETED_MACROTAG = "not_completed" 

    # constant for field 'not_completed' in 'trigger_pritives' section
    TRIGGER_PRIMITIVE_NOT_COMPLETED_MACROTAG = "all_not_completed"
    
    # constant for field 'with_ok_receipts'
    COMPLETED_OK_RECEIPT_TOTAL = "with_ok_receipts"
    
    # constant for field 'with_ko_receipts'
    COMPLETED_KO_RECEIPT_TOTAL = "with_ko_receipts"
    
    # constant for field 'rejected'
    NOT_COMPLETED_REJECTED = "rejected"
    
    # constant for field 'not_sent_end_retry'
    NOT_COMPLETED_NOT_SENT_END_RETRY = "not_sent_end_retry"
    
    # constant for field 'scheduled'
    NOT_COMPLETED_SCHEDULED = "scheduled"
    
    # constant for field 'ongoing'
    NOT_COMPLETED_ONGOING = "ongoing"
    
    # constant for field 'never_sent'
    NOT_COMPLETED_NEVER_SENT = "never_sent"
    
    # constant for field 'receipt_ok_count'
    RECEIPT_OK_COUNT = "receipt_ok_count"
    
    # constant for field 'receipt_ko_count'
    RECEIPT_KO_COUNT = "receipt_ko_count"

    # constant for field 'rpt_timeout' in 'not_completed' section
    TRIGGER_PRIMITIVE_NOT_COMPLETED_RPT_TIMEOUT = 'rpt_timeout_trigger'

    # constant for field 'redirect' in 'not_completed' section
    TRIGGER_PRIMITIVE_NOT_COMPLETED_REDIRECT = 'redirect'

    # constant for field 'receipt_ko' in 'not_completed' section
    TRIGGER_PRIMITIVE_NOT_COMPLETED_RECEIPT_KO = 'receipt_ko'

    # constant for field 'paymenttoken_timeout' in 'not_completed' section
    TRIGGER_PRIMITIVE_NOT_COMPLETED_PAYMENTTOKEN_TIMEOUT = 'payment_token_timeout_trigger'

    # constant for field 'ecommerce_timeout' in 'not_completed' section
    TRIGGER_PRIMITIVE_NOT_COMPLETED_ECOMMERCE_TIMEOUT = 'ecommerce_hang_timeout_trigger'

    # constant for field 'no_state' in 'not_completed' section
    TRIGGER_PRIMITIVE_NOT_COMPLETED_NO_STATE = 'no_state'

    # ================================================
    # Other constants
    # ================================================

    # constant for file name that includes parameters
    PARAMETERS_FILENAME = "config.json"

    # constant for segregation code
    SEGREGATION_CODE_FOR_WISP = 51

    # constant for wildcard character
    WILDCARD_CHARACTER = "*"
