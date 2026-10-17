"""Durable repair findings: failed review requests never erase earlier debt."""
import hashlib, json

def identified(findings):
    result={}
    for finding in findings:
        value={k:finding[k] for k in ('symbol','severity','name','reason')}
        identity=hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
        result[identity]=dict(value,id=identity)
    return list(result.values())

def seed_backlog(db,root):
    rows=db.execute("SELECT * FROM classes WHERE state='needs_review'").fetchall()
    missing=[r for r in rows if 'review_debt' not in json.loads(r['result'] or '{}')]
    if not missing:return
    # The latest provider failure may have overwritten the catalog's last useful
    # review. Recover that review from immutable completed-session receipts.
    latest={}
    for session in db.execute("SELECT id FROM sessions WHERE phase='complete' ORDER BY id"):
        path=root/'rounds'/f"cloud-{session['id']:06d}"/'quality/receipt.json'
        if not path.exists():continue
        receipt=json.loads(path.read_text())
        for item in receipt.get('classes',[]):
            review=item.get('review',{})
            if review.get('status')=='complete':latest[item['original_class']]=(session['id'],review)
    with db:
        for row in missing:
            result=json.loads(row['result'] or '{}');review=result.get('quality_review',{})
            source=row['session']
            if review.get('status')!='complete':source,review=latest.get(row['original'],(source,{}))
            result.update(review_debt=identified(review.get('findings',[])),review_debt_source_session=source)
            db.execute('UPDATE classes SET result=? WHERE original=?',(json.dumps(result),row['original']))

def repair_request(row,manual=None):
    previous=json.loads(row['result'] or '{}')
    findings=previous.get('review_debt')
    if findings is None:findings=identified(previous.get('quality_review',{}).get('findings',[]))
    return dict(original_class=row['original'],after_session=row['session'] or 0,
        source_review_session=previous.get('review_debt_source_session',row['session']),
        findings=findings,previous_unresolved=previous.get('unresolved',[]),
        previous_coverage=previous.get('coverage',{}),manual_followup=manual,
        goal='Repair this previously worked class before any untouched class is admitted. '
             'Check every listed finding against actual code and callers; fix incorrect names '
             'and stale comments, and complete all method arguments and locals. '
             'For each finding ID record the correction or concrete evidence rebutting it. '
             'Do not rename a supported name merely to satisfy a reviewer. '
             'Keep unsupported rename targets and uncertain meanings unresolved. '
             'A fresh independent review, full coverage and verified save must clear the class.')

def validate_assessments(review,evidence):
    findings=(evidence.get('repair_context') or {}).get('findings',[])
    if not findings:return review
    expected={f['id'] for f in findings};seen=set()
    for item in review.get('repair_assessments',[]):
        identity=item.get('id')
        if identity not in expected or identity in seen:raise ValueError('Unknown or duplicate repair finding assessment')
        if item.get('status') not in ('resolved','unresolved') or not isinstance(item.get('evidence'),str) or not item['evidence'].strip():
            raise ValueError('Repair assessment needs a disposition and concrete evidence')
        seen.add(identity)
        if item['status']=='unresolved':review['verdict']='needs_review'
    if seen!=expected:raise ValueError('Independent review omitted prior repair findings')
    return review

def remaining_findings(slot,review):
    findings=(slot.get('repair_request') or {}).get('findings',[])
    resolved={r['id'] for r in review.get('repair_assessments',[]) if r.get('status')=='resolved'} if review.get('status')=='complete' else set()
    previous=[r for r in findings if r['id'] not in resolved]
    new=review.get('findings',[]) if review.get('status')=='complete' else []
    return identified(previous+new)

def progress(catalog,sessions,enabled):
    active=[slot for session in sessions if session['phase']!='complete'
            for slot in json.loads(session['slots']) if slot.get('assignment_kind')=='repair']
    waiting=catalog.get('needs_review',0);in_flight=len(active)
    return dict(enabled=bool(enabled),waiting=waiting,in_flight=in_flight,
                outstanding=waiting+in_flight,discovery_held=bool(enabled and (waiting or in_flight)))
