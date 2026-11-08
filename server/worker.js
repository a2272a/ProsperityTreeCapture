const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type"
};

function json(data, status=200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...cors }
  });
}

function analyze(results) {
  const valid = Array.isArray(results) ? results.filter(x => x==="B" || x==="P" || x==="T").slice(-80) : [];
  const decisive = valid.filter(x => x==="B" || x==="P");
  const recent = decisive.slice(-20);
  const b = recent.filter(x => x==="B").length;
  const p = recent.length - b;
  // Jeffreys-style smoothing toward the long-run baccarat banker baseline.
  const baseline = 0.5068;
  const bProb = (b + 10*baseline) / (recent.length + 10);
  const pProb = 1 - bProb;
  const pick = bProb >= pProb ? "B" : "P";
  const streakSide = decisive.at(-1) || null;
  let streak = 0;
  for (let i=decisive.length-1; i>=0 && decisive[i]===streakSide; i--) streak++;
  const transitions = recent.slice(1).filter((v,i)=>v!==recent[i]).length;
  const transitionRate = recent.length>1 ? transitions/(recent.length-1) : null;
  const scenarios = [];
  function walk(seq, probability, depth, history) {
    if (depth===0) { scenarios.push({sequence:seq, probability}); return; }
    const tail = history.slice(-20);
    const countB = tail.filter(x=>x==="B").length;
    const qB = (countB + 10*baseline)/(tail.length+10);
    walk(seq+"B", probability*qB, depth-1, history.concat("B"));
    walk(seq+"P", probability*(1-qB), depth-1, history.concat("P"));
  }
  walk("",1,3,decisive);
  scenarios.sort((x,y)=>y.probability-x.probability);
  return {
    ok:true, engine:"remote-statistical-v1", hands:valid.length,
    decisiveHands:decisive.length, ties:valid.length-decisive.length,
    recent20:{B:b,P:p}, probability:{B:bProb,P:pProb}, pick,
    streak:{side:streakSide,length:streak},
    transitionRate, scenarios:scenarios.slice(0,8),
    disclaimer:"통계 요약이며 다음 결과를 보장하거나 독립 시행의 확률을 바꾸지 않습니다."
  };
}

export default {
  async fetch(request) {
    if (request.method==="OPTIONS") return new Response(null,{headers:cors});
    const url = new URL(request.url);
    if (url.pathname==="/health" && request.method==="GET") return json({ok:true,service:"baccarat-analysis-api",version:1});
    if (url.pathname==="/analyze" && request.method==="POST") {
      try {
        const body = await request.json();
        if (!Array.isArray(body.results)) return json({ok:false,error:"results 배열이 필요합니다."},400);
        if (body.results.some(x=>!["B","P","T"].includes(x))) return json({ok:false,error:"B/P/T 이외의 값이 포함됐습니다."},400);
        return json(analyze(body.results));
      } catch (_) { return json({ok:false,error:"잘못된 JSON 요청입니다."},400); }
    }
    return json({ok:false,error:"Not found"},404);
  }
};
