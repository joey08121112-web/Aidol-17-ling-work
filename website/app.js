(() => {
  'use strict';

  const scenarios = {
    commute: {
      time: '08:30',
      label: '出发 · 路线规划示意',
      title: '下一站，心里有数。',
      description: '搜索目的地，查看步行、公交或驾车路线。把出发前的安排，梳理得更清楚。',
      user: '从这里去深圳湾公园，怎么走？',
      answer: '在地图中选择当前位置与出行方式，即可查询到达深圳湾公园的路线。',
      note: '交互演示 · 地点为公共地标；此页面不会获取位置或查询实时路线。',
      mode: '路线规划'
    },
    work: {
      time: '10:00',
      label: '工作 · 会议纪要示意',
      title: '专注讨论，留住重点。',
      description: '把讨论整理为清晰的摘要和待办。让有价值的想法，成为可以继续推进的下一步。',
      user: '把这段会议内容整理成纪要。',
      answer: '会议摘要：确定新品介绍页的内容方向。待办：整理产品素材、确认页面文案，并在周五前完成首版评审。',
      note: '交互演示 · 使用虚构会议内容；实际使用需主动开始相应记录功能。',
      mode: '会议纪要'
    },
    return: {
      time: '18:30',
      label: '通勤 · 到达通知草稿示意',
      title: '忙完一天，也记得生活。',
      description: '把一句报平安的话整理好。生成清楚自然的消息草稿，方便你复制后自行发送。',
      user: '帮我写一句，告诉家里我快到了。',
      answer: '消息草稿：我已经在回家的路上，快到啦。不用等我，先吃饭。你可以复制后自行发送。',
      note: '交互演示 · 仅展示到达通知草稿，不会获取后台位置或自动发送通知、消息。',
      mode: '消息草稿'
    },
    life: {
      time: '21:00',
      label: '生活 · 个人记忆问答示意',
      title: '你的日常，值得被记住。',
      description: '回到自己的对话与记忆里，找到说过的小事。熟悉感，来自有依据的理解。',
      user: '昨天我说周末想去哪里？',
      answer: '你昨天提到，周末想去深圳湾公园散步，顺便看看日落。',
      note: '交互演示 · 引用虚构记忆「昨天 20:15：周末想去深圳湾公园散步、看日落」。',
      mode: '个人记忆'
    }
  };

  const memories = {
    coffee: {
      answer: '你提过更喜欢少冰、无糖的拿铁，下午通常会选择低因咖啡。',
      source: '示例记忆 · 昨天 09:10「拿铁少冰无糖，下午想喝低因。」'
    },
    meeting: {
      answer: '示例会议里，你们约定周五评审产品介绍页；你负责先整理产品素材和文案。',
      source: '示例记忆 · 昨天 14:30「周五看首版，我先整理素材和文案。」'
    },
    trip: {
      answer: '你昨天说周末想去深圳湾公园散步，顺便看看日落。',
      source: '示例记忆 · 昨天 20:15「周末想去深圳湾公园散步、看日落。」'
    }
  };

  function initialize() {
    const setText = (id, value) => {
      const element = document.getElementById(id);
      if (element) element.textContent = value;
    };

    const menuToggle = document.getElementById('menu-toggle');
    const mobileNav = document.getElementById('mobile-nav');

    if (menuToggle && mobileNav) {
      const setMenuOpen = (open) => {
        mobileNav.hidden = !open;
        menuToggle.setAttribute('aria-expanded', String(open));
      };

      menuToggle.setAttribute('aria-controls', mobileNav.id);
      setMenuOpen(!mobileNav.hidden);
      menuToggle.addEventListener('click', () => setMenuOpen(mobileNav.hidden));
      mobileNav.addEventListener('click', (event) => {
        if (event.target instanceof Element && event.target.closest('a')) setMenuOpen(false);
      });
      document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && !mobileNav.hidden) {
          setMenuOpen(false);
          menuToggle.focus();
        }
      });
    }

    const revealElements = Array.from(document.querySelectorAll('[data-reveal]'));
    const motionPreference = typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)')
      : null;
    const showAll = () => revealElements.forEach((element) => element.classList.add('is-visible'));

    if (motionPreference?.matches || !('IntersectionObserver' in window)) {
      showAll();
    } else if (revealElements.length) {
      const observer = new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add('is-visible');
            observer.unobserve(entry.target);
          }
        });
      }, { threshold: 0.08, rootMargin: '0px 0px -20px 0px' });

      revealElements.forEach((element) => observer.observe(element));
      if (motionPreference && typeof motionPreference.addEventListener === 'function') {
        motionPreference.addEventListener('change', (event) => {
          if (event.matches) {
            observer.disconnect();
            showAll();
          }
        });
      }
    }

    const scenarioTabs = Array.from(document.querySelectorAll('[role="tab"][data-scenario]'))
      .filter((tab) => Object.prototype.hasOwnProperty.call(scenarios, tab.dataset.scenario));
    const scenarioPanel = document.getElementById('scenario-panel');

    function selectScenario(selectedTab, focus = false) {
      const scene = scenarios[selectedTab.dataset.scenario];
      if (!scene) return;

      scenarioTabs.forEach((tab) => {
        const selected = tab === selectedTab;
        tab.setAttribute('aria-selected', String(selected));
        tab.tabIndex = selected ? 0 : -1;
        tab.classList.toggle('is-active', selected);
      });

      setText('scene-time', scene.time);
      setText('scene-label', scene.label);
      setText('scene-title', scene.title);
      setText('scene-description', scene.description);
      setText('scene-user', scene.user);
      setText('scene-answer', scene.answer);
      setText('scene-note', scene.note);
      setText('phone-mode', scene.mode);

      if (scenarioPanel) {
        scenarioPanel.setAttribute('aria-labelledby', selectedTab.id);
        scenarioPanel.dataset.scenario = selectedTab.dataset.scenario;
      }
      if (focus) selectedTab.focus();
    }

    scenarioTabs.forEach((tab, index) => {
      if (!tab.id) tab.id = `scenario-tab-${tab.dataset.scenario}`;
      if (scenarioPanel) tab.setAttribute('aria-controls', scenarioPanel.id);
      tab.addEventListener('click', () => selectScenario(tab));
      tab.addEventListener('keydown', (event) => {
        let nextIndex;
        if (event.key === 'ArrowRight') nextIndex = (index + 1) % scenarioTabs.length;
        else if (event.key === 'ArrowLeft') nextIndex = (index - 1 + scenarioTabs.length) % scenarioTabs.length;
        else if (event.key === 'Home') nextIndex = 0;
        else if (event.key === 'End') nextIndex = scenarioTabs.length - 1;
        else return;

        event.preventDefault();
        selectScenario(scenarioTabs[nextIndex], true);
      });
    });

    if (scenarioTabs.length) {
      const selectedTab = scenarioTabs.find((tab) => tab.getAttribute('aria-selected') === 'true');
      selectScenario(selectedTab || scenarioTabs[0]);
    }

    const memoryQuestions = Array.from(document.querySelectorAll('.memory-question[data-memory]'))
      .filter((button) => Object.prototype.hasOwnProperty.call(memories, button.dataset.memory));

    function selectMemory(selectedButton) {
      const memory = memories[selectedButton.dataset.memory];
      if (!memory) return;
      memoryQuestions.forEach((button) => {
        const selected = button === selectedButton;
        button.classList.toggle('is-active', selected);
        button.setAttribute('aria-pressed', String(selected));
      });
      setText('memory-answer', memory.answer);
      setText('memory-source', memory.source);
    }

    memoryQuestions.forEach((button) => button.addEventListener('click', () => selectMemory(button)));
    if (memoryQuestions.length) {
      const selectedButton = memoryQuestions.find((button) => button.getAttribute('aria-pressed') === 'true');
      selectMemory(selectedButton || memoryQuestions[0]);
    }

    setText('year', String(new Date().getFullYear()));
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, { once: true });
  else initialize();
})();
