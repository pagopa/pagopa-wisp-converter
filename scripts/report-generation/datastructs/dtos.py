from utility.constants import Constants

class PrimitiveTriggerStatistics:

    def __init__(self):
        self.carts = PrimitiveTriggerCounter()
        self.no_carts = PrimitiveTriggerCounter()


# ============================================================================
   
class PrimitiveTriggerCounter:

    def __init__(self):
        self.count = 0;
        self.session_ids = set()


    def add(self, session_id):
        self.count += 1
        self.session_ids.add(session_id) 


# ============================================================================
   
class CompletedReceiptStatistics:

    def __init__(self):
        self.with_ok_receipts_all_sent = 0
        self.with_ko_receipts_all_sent = 0


    def add_as_ok(self):
        self.with_ok_receipts_all_sent += 1


    def add_as_ko(self):
        self.with_ko_receipts_all_sent += 1
 

# ============================================================================
   
class ReceiptDetailStatistics:

    def __init__(self, receipt_ok_count=0, receipt_ko_count=0):
        self.receipt_ok_count = receipt_ok_count
        self.receipt_ko_count = receipt_ko_count
        self.receipts = []


    def merge(self, stats):
        self.receipt_ok_count += stats.receipt_ok_count
        self.receipt_ko_count += stats.receipt_ko_count
        self.receipts.extend(stats.receipts)


    def add_as_ok(self, receipt_id):
        self.receipt_ok_count += 1
        self.receipts.append(receipt_id)


    def add_as_ko(self, receipt_id):
        self.receipt_ko_count += 1
        self.receipts.append(receipt_id)

    
    def to_dict(self):
        return {
            Constants.RECEIPT_OK_COUNT: self.receipt_ok_count,
            Constants.RECEIPT_KO_COUNT: self.receipt_ko_count,
            "receipts": self.receipts
        }


# ============================================================================
   
class NotCompletedReceiptStatistics:

    def __init__(self):
        
        self.rejected = ReceiptDetailStatistics()
        self.not_sent_end_retry = ReceiptDetailStatistics()
        self.sending_or_scheduled = ReceiptDetailStatistics()
        self.ongoing = ReceiptDetailStatistics()
        self.never_sent = ReceiptDetailStatistics()