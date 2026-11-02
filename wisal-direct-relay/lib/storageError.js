// أسباب أعطال التخزين اللي مسموح تتسجّل. رسالة خطأ قاعدة البيانات نفسها ممكن
// تحمل قيم من الطلب، فعمرها ما بتتسجّل — بنسجّل سبب عام بس.
class StorageNotConfiguredError extends Error {
  constructor() {
    super('relay storage is not configured: set DATABASE_URL');
    this.name = 'StorageNotConfiguredError';
  }
}

function storageErrorReason(err) {
  return err instanceof StorageNotConfiguredError ? 'storage not configured' : 'storage failure';
}

module.exports = { StorageNotConfiguredError, storageErrorReason };
