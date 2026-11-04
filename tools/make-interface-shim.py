import subprocess,re,os,sys
S=sys.argv[1]
OUT=sys.argv[2]
types=['org.bukkit.Server','org.bukkit.World','org.bukkit.block.Block','org.bukkit.entity.Entity','org.bukkit.entity.LivingEntity',
 'org.bukkit.entity.HumanEntity','org.bukkit.entity.Player','org.bukkit.entity.Item','org.bukkit.inventory.Inventory',
 'org.bukkit.advancement.Advancement','org.bukkit.plugin.PluginManager','org.bukkit.scheduler.BukkitScheduler','org.bukkit.scheduler.BukkitTask']
for t in types:
    out=subprocess.run(['javap','-protected','-cp',S,t],capture_output=True,text=True).stdout.splitlines()
    out=[l for l in out if not l.startswith('Picked up')]
    print(t) if False else None
    hdr=[l for l in out if re.match(r'(public|protected) (abstract |final )?(class|interface)',l)][0]
    pkg,name=t.rsplit('.',1)
    m=re.match(r'.*?(?:class|interface) \S+?(<.*?>)?(?: extends ([^{]+?))?(?: implements ([^{]+?))? \{',hdr)
    bases=[]
    if m.group(2): bases+= [x.strip() for x in re.split(r',(?![^<]*>)',m.group(2))]
    if m.group(3): bases+= [x.strip() for x in re.split(r',(?![^<]*>)',m.group(3))]
    bases=[b for b in bases if b!='java.lang.Object']
    gen=m.group(1) or ''
    lines=[f'package {pkg};','',f'public interface {name}{gen}'+(' extends '+', '.join(bases) if bases else '')+' {']
    for l in out:
        l=l.strip()
        if not l.endswith(';') or l.startswith(('public','protected'))==False: continue
        if re.match(r'(public|protected) (abstract |final )?(class|interface)',l): continue
        if '(' not in l: continue   # field
        if re.match(r'(public|protected) (static )?(final )?'+re.escape(t)+r'\(',l) or re.match(r'(public|protected) '+re.escape(t)+r'\(',l): continue
        if re.match(r'(public|protected) [\w.]*'+name+r'\(',l) and ' ' not in l.split('(')[0].replace('public ','').replace('protected ',''): continue  # constructor
        if ' static ' in l: continue
        l=l.replace('protected ','public ').replace('final ','').replace('abstract ','').replace('synchronized ','')
        l=l.replace('public ','',1)
        mm=re.match(r'(.*?)\((.*)\)(\s*throws [^;]+)?;',l)
        if mm:
            params=mm.group(2)
            if params.strip():
                parts=re.split(r',\s*(?![^<]*>)',params)
                params=', '.join(f'{p} a{i}' for i,p in enumerate(parts))
                params=params.replace('... a','... a')
            l=mm.group(1)+'('+params+')'+(mm.group(3) or '')+';'
        lines.append('    '+l)
    lines.append('}')
    d=OUT+'/'+pkg.replace('.','/'); os.makedirs(d,exist_ok=True)
    open(f'{d}/{name}.java','w').write('\n'.join(lines)+'\n')
