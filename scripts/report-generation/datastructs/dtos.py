

class PrimitiveTriggerStatistics:

    def __init__(self):
        self.carts = PrimitiveTriggerCounter()
        self.no_carts = PrimitiveTriggerCounter()


# ============================================================================
   
class PrimitiveTriggerCounter:

    def __init__(self):
        self.count = 0;
        self.session_ids = []


    def add(self, session_id):
        self.count += 1
        self.session_ids.append(session_id) 


# ============================================================================
   
class CompletedReceiptStatistics:

    def __init__(self):
        self.sent_ok_receipts = 0
        self.sent_ko_receipts = 0
        self.ok_receipt_sent_by_retry = 0
        self.ko_receipt_sent_by_retry = 0


    def add_as_ok(self):
        self.sent_ok_receipts += 1


    def add_as_ko(self):
        self.sent_ko_receipts += 1


    def add_as_ok_receipt_sent_by_retry(self):
        self.ok_receipt_sent_by_retry += 1
 

# ============================================================================
   
class ReceiptDetailStatistics:

    def __init__(self, count_ok=0, count_ko=0):
        self.count_ok = count_ok
        self.count_ko = count_ko
        self.receipts = []

    def merge(self, stats):
        self.count_ok += stats.count_ok
        self.count_ko += stats.count_ko
        self.receipts.extend(stats.receipts)


    def add_as_ok(self, receipt_id):
        self.count_ok += 1
        self.receipts.append(receipt_id)


    def add_as_ko(self, receipt_id):
        self.count_ko += 1
        self.receipts.append(receipt_id)

    
    def to_dict(self):
        return {
            "count_ok": self.count_ok,
            "count_ko": self.count_ko,
            "receipts": self.receipts
        }


# ============================================================================
   
class NotCompletedReceiptStatistics:

    def __init__(self):
        
        self.rejected = ReceiptDetailStatistics()
        self.never_sent = ReceiptDetailStatistics()
        self.sending_or_scheduled = ReceiptDetailStatistics()
        self.blocked = ReceiptDetailStatistics()