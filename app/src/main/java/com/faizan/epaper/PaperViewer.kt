package com.faizan.epaper

interface PaperViewer {
    fun renderLoadingPage(page: NewspaperPage): String

    fun renderPage(
        page: NewspaperPage,
        hotspots: List<NewspaperHotspot>,
        paper: Paper,
        directory: java.io.File
    ): String

    fun renderArticle(
        edition: NewspaperEdition,
        article: NewspaperArticle,
        paper: Paper,
        directory: java.io.File?
    ): String
}

class WebPaperViewer : PaperViewer {
    override fun renderLoadingPage(page: NewspaperPage): String =
        "<html><body style='font:18px sans-serif;padding:24px'>Loading ${escape(page.title)}…</body></html>"

    override fun renderPage(
        page: NewspaperPage,
        hotspots: List<NewspaperHotspot>,
        paper: Paper,
        directory: java.io.File
    ): String {
        val imageFileName = if (java.io.File(directory, "${page.id}.jpg").isFile) {
            "${page.id}.jpg"
        } else {
            "${page.id}.png"
        }
        val areas = hotspots.joinToString("") { hotspot ->
            val route = if (hotspot.type == NewspaperHotspotType.TEXT) "text" else "image"
            """<area shape="${escape(hotspot.shape)}" coords="${hotspot.coordinates.joinToString(",")}" href="${paper.offlineOrigin}$route/${escape(hotspot.targetId)}" alt="${escape(hotspot.label)}" title="${escape(hotspot.label)}">"""
        }
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{margin:0;background:#222;text-align:center}
            area,a{-webkit-tap-highlight-color:transparent;outline:none}
            area:focus,area:active,a:focus,a:active{outline:none}
            #paper-container{position:relative;width:100%}
            #page{display:block;width:100%;height:auto;margin:auto}
            .hotspot-highlight{position:absolute;z-index:2;pointer-events:none;
              background:rgba(255,210,0,.32);border:3px solid #ffd400;box-sizing:border-box;
              box-shadow:0 0 12px rgba(255,212,0,.9)}</style>
            </head><body><div id="paper-container"><img id="page" src="$imageFileName" usemap="#page-map"><map id="page-map" name="page-map">$areas</map></div>
            <script>
            (function(){const img=document.getElementById('page');
            function scale(){if(!img.naturalWidth)return;const sx=img.clientWidth/img.naturalWidth,sy=img.clientHeight/img.naturalHeight;
            document.querySelectorAll('area').forEach(a=>{if(!a.dataset.original)a.dataset.original=a.coords;const c=a.dataset.original.split(',').map(Number);
            a.coords=c.map((n,i)=>Math.round(n*(i%2?sy:sx))).join(',')})}
            window.updatePageHotspots=function(items){const map=document.getElementById('page-map');map.replaceChildren();
            items.forEach(item=>{const area=document.createElement('area');area.shape=item.shape;
            area.coords=item.coordinates.join(',');area.href='${paper.offlineOrigin}'+item.route+'/'+item.targetId;
            area.alt=item.label;area.title=item.label;map.appendChild(area)});scale()};
            document.getElementById('page-map').addEventListener('click',function(event){
            const area=event.target.closest('area');if(!area)return;
            const shape=area.shape.toLowerCase(),coords=area.coords.split(',').map(Number);
            if((shape==='circle'&&coords.length<3)||(shape!=='circle'&&coords.length<4))return;
            event.preventDefault();
            let left,top,right,bottom;
            if(shape==='circle'){
            left=coords[0]-coords[2];top=coords[1]-coords[2];
            right=coords[0]+coords[2];bottom=coords[1]+coords[2]}
            else{const xs=coords.filter((_,i)=>i%2===0),ys=coords.filter((_,i)=>i%2===1);
            left=Math.min(...xs);top=Math.min(...ys);right=Math.max(...xs);bottom=Math.max(...ys)}
            const mark=document.createElement('div');mark.className='hotspot-highlight';
            mark.style.left=left+'px';mark.style.top=top+'px';
            mark.style.width=(right-left)+'px';mark.style.height=(bottom-top)+'px';
            if(shape==='circle')mark.style.borderRadius='50%';
            document.getElementById('paper-container').appendChild(mark);const target=area.href;
            setTimeout(()=>{location.href=target},180)});
            img.addEventListener('load',scale);new ResizeObserver(scale).observe(img);scale();})();
            </script></body></html>
        """.trimIndent()
    }

    override fun renderArticle(
        edition: NewspaperEdition,
        article: NewspaperArticle,
        paper: Paper,
        directory: java.io.File?
    ): String {
        val paragraphs = article.paragraphs.joinToString("") {
            "<p>${escape(it)}</p>"
        }
        val images = article.imageUrls.mapIndexed { index, imageUrl ->
            val localImage = directory?.let {
                edition.articleImageFile(it, article, index)
            }?.takeIf { it.isFile }?.let {
                paper.localArticleImageUrl(article.id, index)
            }
            val image = """<img src="${escape(localImage ?: imageUrl)}" alt="" loading="lazy">"""
            val target = if (paper.supportsInlineArticleImages) {
                paper.inlineArticleImageTarget(article.id, index)
            } else {
                null
            }
            if (target != null) {
                """<a href="${escape(target)}">$image</a>"""
            } else {
                image
            }
        }.joinToString("")
        val imageButton = paper.articleImageDetailTarget(article.id)?.let { target ->
            """
                <a class="image-button" href="${escape(target)}">Display as image</a>
            """.trimIndent()
        }.orEmpty()
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{max-width:760px;margin:24px auto;padding:0 20px;font:18px/1.65 Georgia,serif;color:#222}
            h1{font-size:30px;line-height:1.2}.byline{color:#666;font:14px Arial,sans-serif;margin:12px 0 24px}
            p{margin:0 0 1em}img{display:block;max-width:100%;height:auto;margin:20px auto}
            .image-button{display:inline-block;margin:8px 0 18px;padding:10px 16px;
            border-radius:4px;background:#244d70;color:#fff;text-decoration:none;font:14px Arial,sans-serif}
            </style></head><body><h1>${escape(article.title)}</h1>
            <div class="byline">${escape(article.byline)}</div>$imageButton$images$paragraphs</body></html>
        """.trimIndent()
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
}
