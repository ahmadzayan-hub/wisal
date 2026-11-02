// نسخة تخزين واحدة مشتركة بين كل الـ handlers داخل نفس الـ process.
// - DATABASE_URL متظبط: PostgreSQL (lib/pgStore.js) — ده المطلوب في الإنتاج.
// - إنتاج من غير DATABASE_URL: تخزين بيرفض كل عملية (503) بدل ما يشتغل
//   بصمت على ذاكرة بتضيع مع كل cold start.
// - غير كده (تطوير/اختبار محلي): في الذاكرة.
const { selectStore } = require('./selectStore');

module.exports = selectStore();
