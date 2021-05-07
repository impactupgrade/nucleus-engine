var ds = new function () {
  let primary_color = "#3399CC";
  var ds_submitted = false;
  const root_url = "https://nucleus.impactupgrade.com/ds/";

  function loadScript(src, callback ) {
    var script = document.createElement("script");
    script.setAttribute("src", src);
    script.addEventListener("load", callback);
    var firstScriptTag = document.getElementsByTagName('script')[0];
    firstScriptTag.parentNode.insertBefore(script, firstScriptTag);
  };

  this.init = function (args) {
    args.ds_api_key = "REPLACE_ME_DS_API_KEY";
    args.stripe_public_key = "REPLACE_ME_STRIPE_PUBLIC_KEY";

    primary_color = args.primary_color || primary_color;
    toHSL(primary_color);

    document.head.insertAdjacentHTML("beforeend", `
    <style>
      #DSFORM-INLINE{
        transition: height 0.5s linear;
      }
      @media only screen and (max-width: 446px) {
        #DSREMIND{
          inset: auto 0 10px auto !important;
        }
      }
    </style>
    `)

    
    window.onmessage = (event) => {
      var data = JSON.parse(event.data);
      switch (data.action) {
        case 'show_form':
          showform();
          break;
        case 'hide_form':
          hideform();
          break;
        case 'form_submited':
          ds_submitted = true;
          break;
        case 'button_loaded':
          var ds_color = document.documentElement.style.getPropertyValue('--ds_color');
          var ds_l = document.documentElement.style.getPropertyValue('--ds_l');
          event.source.window.postMessage('{"action": "set_color", "ds_color":"' + ds_color + '", "ds_l":"' + ds_l + '"}', '*')
          break;
        case 'form_loaded':
          var ds_color = document.documentElement.style.getPropertyValue('--ds_color');
          var ds_l = document.documentElement.style.getPropertyValue('--ds_l');
          event.source.window.postMessage('{"action": "set_color", "ds_color":"' + ds_color + '", "ds_l":"' + ds_l + '"}', '*')
          event.source.window.postMessage('{"action": "load_options", "args": ' + JSON.stringify(args) + '}', '*')
          break;
        case 'reminder_loaded':
          var ds_color = document.documentElement.style.getPropertyValue('--ds_color');
          var ds_l = document.documentElement.style.getPropertyValue('--ds_l');
          event.source.window.postMessage('{"action": "set_color", "ds_color":"' + ds_color + '", "ds_l":"' + ds_l + '"}', '*')
          break;
        case 'hide_reminder':
          hidereminder();
          break;
        case 'set_iframe_size':
          document.querySelector("#DSFORM-INLINE").style.height = (data.args.height + 40) + 'px';
          break;
      }
    }

    loadScript("https://js.stripe.com/v3/", function () {
      var ds_displays = document.querySelectorAll('[data-ds-display]');
      for (var i in ds_displays) {
        if (ds_displays.hasOwnProperty(i)) {
          switch (ds_displays[i].getAttribute('data-ds-display')) {
            case "button":
              ds.options = args;
              addbutton();
              addform(false);
              addreminder();
              break;
            case "form":
              addform(true);
              break;
          }
        }
      }
    });
  }

  showform = function () {
    form = document.getElementById('DSFORM');
    document.body.style.overflow = "hidden"
    form.style.display = "block";
    form.focus();
    form.addEventListener('focusout', function (event) {
      event.preventDefault();
      event.stopPropagation();
      form.focus();
    });
    hidereminder();
  };

  hideform = function () {
    document.body.style.overflow = "inherit"
    document.getElementById('DSFORM').style.display = "none";
    if (!ds_submitted){
      showreminder();
    }
  };

  showreminder = function () {
    document.getElementById('DSREMIND').style.display = "block";
  };

  hidereminder = function () {
    document.getElementById('DSREMIND').style.display = "none";
  };

  addbutton = function () {
    document.querySelector('[data-ds-display="button"]').innerHTML += '<iframe width="125" title="Donation Spring" height="40" src="' + root_url +'button.html" frameborder="0" scrolling="no" marginheight="0" marginwidth="0" style="visibility: visible; display: inline-block !important; vertical-align: middle !important; width: 125px !important; min-width: 40px !important; max-width: 125px !important; height: 40px !important; min-height: 40px !important; max-height: 125px !important;" name="DSDB" id="DSDB"></iframe>'
  };

  addform = function(inline) {
    dsform = document.createElement('iframe');
    dsform.setAttribute('frameborder', '0');
    dsform.setAttribute('title', 'Donation Spring Form');
    if (inline) {
      dsform.setAttribute('id', 'DSFORM-INLINE');
      dsform.setAttribute('name', 'DSFORM-INLINE');
      dsform.setAttribute('scrolling', 'no');
      dsform.setAttribute('style', 'margin: 0 !important;padding: 0 !important;border: 0 !important; max-width: 580px; min-width: 300px; width: 100%; !important; min-height: 590px; height: 100%;');
    } else {
      dsform.setAttribute('id', 'DSFORM');
      dsform.setAttribute('name', 'DSFORM');
      dsform.setAttribute('style', 'display: none; margin: 0 !important;padding: 0 !important;border: 0 !important; width: 100% !important; height: 100% !important; position: fixed !important; opacity: 1 !important; top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; z-index: 2147483646 !important;');
    }
    if (inline) {
      dsform.setAttribute('src', root_url + 'form.html');
      document.querySelector('[data-ds-display="form"]').appendChild(dsform);
    } else {
      dsform.setAttribute('src', root_url + 'form.html');
      dsform.setAttribute('onload', 'this.contentWindow.focus()');
      document.body.appendChild(dsform);
    }
  };

  addreminder = function () {
    dsreminder = document.createElement('iframe');
    dsreminder.setAttribute('id', 'DSREMIND');
    dsreminder.setAttribute('frameborder', '0');
    dsreminder.setAttribute('scrolling', 'no');
    dsreminder.setAttribute('marginheight', '0');
    dsreminder.setAttribute('marginwidth', '0');
    dsreminder.setAttribute('title', 'Donation Spring Reminder');
    dsreminder.setAttribute('style', 'display: none !important; visibility: visible; !important; opacity: 1 !important; inset: auto 10px 10px auto; position: fixed !important; z-index: 2147483644 !important; margin: 0px !important; padding: 0px !important; height: 110px !important; min-height: 110px !important; max-height: 310px !important; width: 100% !important; min-width: 300px !important; max-width: 425px !important;');
    dsreminder.setAttribute('src', root_url + 'reminder.html');
    document.body.appendChild(dsreminder);
  };

  toHSL = function (hex) {
    var result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
    var r = parseInt(result[1], 16);
    var g = parseInt(result[2], 16);
    var b = parseInt(result[3], 16);
    r /= 255, g /= 255, b /= 255;
    var max = Math.max(r, g, b), min = Math.min(r, g, b);
    var h, s, l = (max + min) / 2;
    if (max == min) {
      h = s = 0; // achromatic
    } else {
      var d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      switch (max) {
        case r: h = (g - b) / d + (g < b ? 6 : 0); break;
        case g: h = (b - r) / d + 2; break;
        case b: h = (r - g) / d + 4; break;
      }
      h /= 6;
    };
    s = s * 100;
    s = Math.round(s);
    l = l * 100;
    l = Math.round(l);
    h = Math.round(360 * h);
    document.documentElement.style.setProperty('--ds_color', h + ', ' + s + '%');
    document.documentElement.style.setProperty('--ds_l', l + '%');
  }

}