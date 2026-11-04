/* ============================================================
   quiz.js — 共用测验组件
   用法：
     <div class="quiz" data-quiz>
       <p class="q">题干…</p>
       <button class="opt" data-correct="false">选项A</button>
       <button class="opt" data-correct="true">选项B</button>
       <button class="opt" data-correct="false">选项C</button>
       <p class="feedback"></p>
     </div>
   点任意选项即判分并锁定。正确/错误即时反馈（紧密反馈环）。
   规则提醒：三个选项的字数/字数应尽量一致，正确答案位置在不同题间要打散。
   ============================================================ */
(function () {
  document.querySelectorAll('[data-quiz]').forEach(function (quiz) {
    var opts = quiz.querySelectorAll('.opt');
    var fb = quiz.querySelector('.feedback');
    var done = false;
    opts.forEach(function (btn) {
      btn.addEventListener('click', function () {
        if (done) return;
        done = true;
        var isCorrect = btn.getAttribute('data-correct') === 'true';
        opts.forEach(function (b) {
          b.disabled = true;
          if (b.getAttribute('data-correct') === 'true') b.classList.add('correct');
        });
        if (!isCorrect) btn.classList.add('wrong');
        if (fb) {
          fb.className = 'feedback ' + (isCorrect ? 'ok' : 'no');
          fb.textContent = isCorrect
            ? '✓ 对。' + (btn.getAttribute('data-why') || '')
            : '× 再看一眼上面的解释：正确答案已标绿。';
        }
      });
    });
  });
})();
