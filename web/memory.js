/** Memory workspace: evidence, reviewable meeting drafts and user-owned task state. */
export function createMemoryWorkspace({api, toast, refresh, getData, speak, escape, date}) {
  const $ = id => document.getElementById(id);
  const kinds = {task:'待办线索', fact:'有效信息', preference:'个人偏好', trip:'行程计划'};
  const methods = {llm:'AI 整理 · 请核对', rules:'本地规则整理', rules_fallback:'模型暂不可用 · 本地整理'};
  let answer = '', report = null, evidenceSignature = '', revision = 0;
  const sourceName = source => source === 'user' ? '你主动记下' : source?.startsWith('demo') ? '模拟演示' : source?.startsWith('transcript:') ? '摘自原始对话' : '对话提取';
  const sourceHTML = sources => `<details class="evidence"><summary>查看 ${sources.length} 条原话依据</summary>${sources.map(s => `<blockquote><small>${escape(s.speaker || s.kind || '原始记录')} · ${date(s.created_at)}</small><p>${escape(s.text)}</p></blockquote>`).join('')}</details>`;

  function renderMemories() {
    const data = getData(); if (!data) return;
    const query = $('memory-search').value.toLowerCase(), kind = $('memory-filter').value;
    const rows = data.memories.filter(m => (!kind || m.kind === kind) && (!$('pinned-only').checked || m.pinned) && m.content.toLowerCase().includes(query));
    let group = '';
    $('memory-list').innerHTML = rows.length ? rows.map(m => {
      const day = m.pinned ? '★ 重要收藏' : new Date(m.created_at).toLocaleDateString('zh-CN',{month:'long',day:'numeric',weekday:'short',timeZone:data.settings.timezone});
      const heading = day !== group ? `<h4 class="memory-day">${escape(day)}</h4>` : ''; group = day;
      return `${heading}<article class="memory-capsule ${m.pinned ? 'is-pinned' : ''}"><div class="capsule-meta"><span class="kind-tag">${kinds[m.kind]}</span><small>${date(m.created_at)} · ${sourceName(m.source)}${m.updated_at ? ' · 已修改' : ''}</small></div><p>${escape(m.content)}</p><div class="capsule-actions"><button data-pin="${m.id}" aria-pressed="${!!m.pinned}">${m.pinned ? '★ 已收藏' : '☆ 重要收藏'}</button><button data-edit="${m.id}">更正内容</button>${/^(demo_)?transcript:/.test(m.source)?`<button data-source="${m.id}">查看原话</button>`:''}<button data-delete="${m.id}">删除</button></div></article>`;
    }).join('') : '<p class="empty-compact">没有匹配的记忆。你可以在上方直接记下一件事。</p>';
  }
  function resetEditor() {
    $('memory-edit-id').value=''; $('memory-content').value=''; $('memory-save').textContent='立即记住'; $('memory-cancel').hidden=true;
  }
  $('memory-search').oninput=renderMemories; $('memory-filter').onchange=renderMemories; $('pinned-only').onchange=renderMemories;
  $('memory-cancel').onclick=resetEditor;
  $('memory-list').onclick=async event => {
    const button=event.target.closest('button'); if(!button)return;
    const id=button.dataset.pin || button.dataset.edit || button.dataset.delete || button.dataset.source;
    const memory=getData()?.memories.find(m=>m.id===id);if(!memory)return;
    if(button.dataset.source){
      button.disabled=true;
      try{
        const source=await api(`/memories/${id}/source`);
        const article=button.closest('article');article.querySelector('.capsule-source')?.remove();
        const detail=document.createElement('div');detail.className='capsule-source';detail.innerHTML=sourceHTML([source]);article.append(detail);detail.querySelector('details').open=true;
      }catch(error){toast(error.message);}finally{button.disabled=false;}return;
    }
    if(button.dataset.edit){
      $('memory-edit-id').value=id; $('memory-kind').value=memory.kind; $('memory-content').value=memory.content;
      $('memory-save').textContent='保存更正'; $('memory-cancel').hidden=false; $('memory-content').focus();return;
    }
    button.disabled=true;
    try{
      if(button.dataset.delete){await api(`/memories/${id}`,{method:'DELETE'});if($('memory-edit-id').value===id)resetEditor();}
      else await api(`/memories/${id}`,{method:'PUT',body:JSON.stringify({content:memory.content,kind:memory.kind,pinned:!memory.pinned})});
      await refresh();
    }catch(error){toast(error.message);}finally{button.disabled=false;}
  };
  $('memory-form').onsubmit=async event=>{
    event.preventDefault();const content=$('memory-content').value.trim();if(!content)return;
    const id=$('memory-edit-id').value, previous=getData()?.memories.find(m=>m.id===id);
    const button=$('memory-save');button.disabled=true;
    try{
      await api(id?`/memories/${id}`:'/memories',{method:id?'PUT':'POST',body:JSON.stringify({content,kind:$('memory-kind').value,...(id?{pinned:!!previous?.pinned}:{})})});
      resetEditor();await refresh();toast(id?'记忆已更正，之后的检索将使用新内容':'已为你记住');
    }catch(error){toast(error.message);}finally{button.disabled=false;}
  };

  function renderReport(value) {
    report=value;$('meeting-copy').disabled=!report;
    if(!report){$('meeting-result').innerHTML='<p class="empty-compact">完成工作对话后生成纪要；纪要与待办保存在本机。</p>';return;}
    const sources=new Map((report.sources || []).map(s=>[s.id,s]));
    $('meeting-result').innerHTML=`<div class="insight-meta"><span>${methods[report.extraction] || '待审核草稿'}</span><span>${date(report.created_at)}${report.includes_demo?' · 含模拟演示':''}</span></div><p class="meeting-summary">${escape(report.summary)}</p>${report.decisions.length?`<h4>已记录的结论</h4><ul class="decision-list">${report.decisions.map(d=>`<li>${escape(d.text)}${sourceHTML(d.source_ids.map(id=>sources.get(id)).filter(Boolean))}</li>`).join('')}</ul>`:''}${sourceHTML([...sources.values()])}`;
  }
  function renderTasks() {
    const filter=$('task-filter').value;
    const tasks=(getData()?.action_tasks || []).filter(t=>filter==='all' || t.status===filter);
    $('action-task-list').innerHTML=tasks.length?tasks.map(t=>`<article class="action-task ${t.status==='done'?'done':''}"><label><input type="checkbox" data-task="${t.id}" ${t.status==='done'?'checked':''}><span>${escape(t.content)}</span></label><small>负责人：${escape(t.owner || '未明确')} · 截止：${escape(t.due || '未明确')}</small><span class="task-status">${t.status==='done'?'你已确认完成':'待核对 / 待完成'}</span></article>`).join(''):'<p class="empty-compact">这里还没有待办。生成会议纪要后，明确的行动项会显示在这里。</p>';
  }
  $('task-filter').onchange=renderTasks;
  $('action-task-list').onchange=async event=>{
    const box=event.target;if(!box.dataset.task)return;box.disabled=true;
    try{await api(`/tasks/${box.dataset.task}`,{method:'PATCH',body:JSON.stringify({status:box.checked?'done':'open'})});await refresh();}
    catch(error){box.checked=!box.checked;toast(error.message);}finally{box.disabled=false;}
  };
  $('meeting-generate').onclick=async()=>{
    const button=$('meeting-generate');button.disabled=true;button.textContent='正在整理…';
    try{
      const result=await api('/meetings/summarize',{method:'POST',body:JSON.stringify({hours:Number($('meeting-hours').value),include_demo:$('insight-demo').checked})});
      await refresh();renderReport(result);toast(result.cached?'这批记录已整理，待办不会重复创建':'纪要与待办已生成，请核对原话');
    }catch(error){toast(error.message);}finally{button.disabled=false;button.textContent='✧ 生成纪要与待办';}
  };
  $('meeting-copy').onclick=async()=>{
    if(!report)return;
    const text=[`工作记录纪要（待核对）`,report.summary,...report.decisions.map(d=>`• ${d.text}`),...report.tasks.map(t=>`□ ${t.content} / ${t.owner||'负责人未明确'} / ${t.due||'截止未明确'}`)].join('\n');
    try{await navigator.clipboard.writeText(text);toast('纪要已复制');}catch{toast('复制失败，请手动选择纪要内容');}
  };
  $('recall-form').onsubmit=async event=>{
    event.preventDefault();const question=$('recall-question').value.trim();if(!question)return;
    const button=$('recall-button'), requestedRevision=revision;button.disabled=true;button.textContent='正在找回…';answer='';$('recall-speak').disabled=true;
    $('recall-result').textContent='正在查找相关的记忆与原话…';
    try{
      const result=await api('/recall',{method:'POST',body:JSON.stringify({question,include_demo:$('insight-demo').checked})});
      if(requestedRevision!==revision)throw new Error('资料刚刚发生变化，请重新提问以获取最新结果');
      answer=result.answer;
      $('recall-result').innerHTML=`<div class="insight-meta">${result.insufficient?'暂时没有足够依据':result.extraction==='llm'?'AI 选择的相关原话':'本地检索摘录'}</div><p class="recall-answer">${escape(answer)}</p>${sourceHTML(result.citations || [])}`;
      $('recall-speak').disabled=!answer;
    }catch(error){$('recall-result').textContent=error.message;toast(error.message);}finally{button.disabled=false;button.textContent='找回线索 ↗';}
  };
  document.querySelectorAll('[data-question]').forEach(button=>button.onclick=()=>{$('recall-question').value=button.dataset.question;$('recall-form').requestSubmit();});
  $('recall-speak').onclick=()=>{if(answer)speak(answer).catch(error=>toast(error.message));};

  return {render(data){
    const signature=JSON.stringify([data.memories,data.action_tasks,data.transcripts]);
    if(signature!==evidenceSignature){
      evidenceSignature=signature;revision++;
      if(answer){answer='';$('recall-speak').disabled=true;$('recall-result').innerHTML='<p class="empty-compact">资料已更新，再问一次可获得最新线索。</p>';}
    }
    renderMemories();renderTasks();renderReport(data.meeting_report);
  }};
}
